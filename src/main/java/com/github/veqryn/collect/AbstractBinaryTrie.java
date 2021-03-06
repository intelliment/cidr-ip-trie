/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.github.veqryn.collect;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;


/**
 * Implementation of the {@link Trie} interface, as an
 * uncompressed binary bitwise trie. For more information, see:
 * <a href="http://en.wikipedia.org/wiki/Trie">wikipedia entry on tries</a>.
 *
 * <p>
 * Works best with short binary data, such as IP addresses or CIDR ranges.
 *
 * Keys will be analyzed as they come in by a {@link KeyCodec}, for the
 * length of their elements, and for the element at any index belonging in
 * either the left or right nodes in a tree. A single empty root node is our
 * starting point, with nodes containing references to their parent, their
 * left and right children if any, and their value if any. Leaf nodes must
 * always have a value, but intermediate nodes may or may not have values.
 *
 * <p>
 * Keys and Values may never be {@code null}, and therefore if any node
 * has a value, it implicitly has a key.
 *
 * <p>
 * Keys and Values are returned in an order according to the order of the
 * elements in the key, and the number of elements in the key.
 *
 * @author Chris Duncan
 *
 * @param <K> Key
 * @param <V> Value
 */
public class AbstractBinaryTrie<K, V> implements Trie<K, V>, Serializable, Cloneable {

  private static final long serialVersionUID = -6697831108554350305L;

  /** The {@link KeyCodec} being used to analyze keys */
  protected final KeyCodec<K> codec;

  /** The entry point for the start of any lookup. Root can not hold a value. */
  protected transient Node<K, V> root = new Node<K, V>(null); // final

  protected transient long size = 0;

  protected transient int modCount = 0;

  protected transient Set<Map.Entry<K, V>> entrySet = null;
  protected transient Set<K> keySet = null;
  protected transient Collection<V> values = null;



  // Constructors:

  /**
   * Create an empty {@link AbstractBinaryTrie} using the given
   * {@link KeyCodec}, and settings for keeping/caching or not of keys after
   * insertion and writing out or not of keys during serialization.
   *
   * @param keyCodec KeyCodec for analyzing of keys
   */
  public AbstractBinaryTrie(final KeyCodec<K> keyCodec) {
    if (keyCodec == null) {
      throw new NullPointerException("KeyCodec may not be null");
    }
    this.codec = keyCodec;
  }

  /**
   * Create a {@link AbstractBinaryTrie} using the given {@link KeyCodec},
   * and settings for keeping/caching or not of keys after insertion and
   * writing out or not of keys during serialization. The trie will be filled
   * with the keys and values in the provided map.
   *
   * @param keyCodec KeyCodec for analyzing of keys
   * @param otherMap Map of keys and values, which will be {@link #putAll}
   *        into the newly created trie
   */
  public AbstractBinaryTrie(final KeyCodec<K> keyCodec, final Map<K, V> otherMap) {
    this(keyCodec);
    this.putAll(otherMap);
  }

  /**
   * Copy constructor, creates a shallow copy of this
   * {@link AbstractBinaryTrie} instance.
   *
   * @param otherTrie AbstractBinaryTrie
   */
  public AbstractBinaryTrie(final AbstractBinaryTrie<K, V> otherTrie) {
    this(otherTrie.codec);
    this.buildFromExisting(otherTrie);
  }



  // Nodes:

  /** Internal representation of a Node Entry */
  protected static final class Node<K, V> implements Serializable {
    // Does not implement java.util.Map.Entry so that we do not accidentally
    // return a Node instance from a public method

    private static final long serialVersionUID = -1866950138115794051L;

    /**
     * Do not directly reference <code>privateKey</code> expecting a non-null key.
     * Instead use {@link AbstractBinaryTrie#resolveKey(Node, AbstractBinaryTrie)}
     * or {@link AbstractBinaryTrie#resolveNode(Node, AbstractBinaryTrie)} to
     * first create the key if it does not exist, and return the cached key.
     *
     * @return the key (K) if it has been resolved, or null otherwise.
     */
    private transient K privateKey = null;

    /**
     * the value (V) or null if this node does not have a value
     */
    protected V value = null;

    protected Node<K, V> left = null;
    protected Node<K, V> right = null;
    protected final Node<K, V> parent; // only root has null parent

    /**
     * Create a new empty Node, with the given parent
     *
     * @param parent Node
     */
    protected Node(final Node<K, V> parent) {
      this.parent = parent;
    }

    /**
     * Return the left or right child Node under this Node,
     * or create and return an empty child if it does not already exist
     *
     * @param leftNode true if this should return the left child,
     *        false if this should return the right child
     * @return left or right child node
     */
    protected final Node<K, V> getOrCreateEmpty(final boolean leftNode) {
      if (leftNode) {
        if (left == null) {
          left = new Node<K, V>(this);
        }
        return left;
      } else {
        if (right == null) {
          right = new Node<K, V>(this);
        }
        return right;
      }
    }

    /**
     * @return true if this Entry node has no value and no child nodes
     */
    protected final boolean isEmpty() {
      return value == null && left == null && right == null;
    }

    /**
     * Replaces the value currently associated with the key with the given value.
     *
     * @param value the new value
     * @return the value associated with the key before this method was called
     */
    protected final V setValue(final V value) {
      final V oldValue = this.value;
      this.value = value;
      return oldValue;
    }

    /**
     * @return CodecElements instance consisting of {@code levelsDeep} int
     *         representing how far from the root this Node was found, and
     *         {@code bits} BitSet representing the elements
     */
    protected final CodecElements getCodecElements() {
      // This will ONLY ever be called if outputting keys,
      // such as keySet/entrySet/toString

      if (this.parent == null) {
        return null; // We are the root node
      }

      final BitSet bits = new BitSet();
      int levelsDeep = 0;
      Node<K, V> node = this;
      while (node.parent != null) {
        if (node.parent.right == node) {
          bits.set(levelsDeep);
        }
        node = node.parent;
        levelsDeep++;
      }

      return new CodecElements(bits, levelsDeep);
    }

    /**
     * hashCode should never be called on a Node.
     * Instead, export the node into a resolved Entry,
     * or just use the value and resolved key separately.
     */
    @Override
    public final int hashCode() {
      throw new IllegalStateException("Nodes should not be hashed or compared");
    }

    /**
     * equals should never be called on a Node.
     * Instead, export the node into a resolved Entry,
     * or just use the value and resolved key separately.
     */
    @Override
    public final boolean equals(final Object obj) {
      throw new IllegalStateException("Nodes should not be hashed or compared");
    }

    @Override
    public final String toString() {
      return (privateKey != null ? privateKey : getCodecElements()) + "=" + value;
    }
  }



  // Key Resolution/Recreation and Export Methods:

  /**
   * Object to hold the information needed by the {@link KeyCodec}
   * to recreate a key. Created because Java does not have tuples, and
   * Node must be able to return the two pieces of information we need to
   * know in order to recreate the key: {@code levelsDeep} int representing
   * how far from the root this Node was found, and {@code bits} BitSet
   * representing the elements.
   */
  protected static final class CodecElements implements Serializable {

    private static final long serialVersionUID = 361855129249641777L;

    /** int representing how far from the root this Node was found */
    protected final BitSet bits;

    /** BitSet representing the elements */
    protected final int levelsDeep;

    /**
     * @param bits the BitSet
     * @param levelsDeep the number of nodes deep
     */
    protected CodecElements(final BitSet bits, final int levelsDeep) {
      this.bits = bits;
      this.levelsDeep = levelsDeep;
    }

    @Override
    public final int hashCode() {
      throw new IllegalStateException("CodecElements should not be hashed or compared");
    }

    @Override
    public final boolean equals(final Object obj) {
      throw new IllegalStateException("CodecElements should not be hashed or compared");
    }

    @Override
    public final String toString() {
      return levelsDeep + "/" + bits;
    }
  }


  /**
   * Ensure a Node's key has been resolved (it is non-null), otherwise recreate
   * it and cache it, before returning the Key.
   *
   * @param node The Node to be resolved
   * @param trie The Trie this node belongs to
   * @return non-null Key for a Node
   */
  protected static final <K, V> K resolveKey(final Node<K, V> node,
      final AbstractBinaryTrie<K, V> trie) {
    final Node<K, V> resolved = resolveNode(node, trie);
    return resolved == null ? null : resolved.privateKey;
  }

  /**
   * Ensure a Node's key has been resolved (it is non-null), otherwise recreate
   * it and cache it, before returning the Node.
   *
   * @param node The Node to be resolved
   * @param trie The Trie this node belongs to
   * @return Node with a non-null Key
   */
  protected static final <K, V> Node<K, V> resolveNode(final Node<K, V> node,
      final AbstractBinaryTrie<K, V> trie) {

    if (node == null || node.parent == null) {
      return null; // If no parents, then it is the root node
    }

    // key has already been resolved, or we shouldn't have a key because we don't have a value
    if (node.privateKey != null || node.value == null) {
      return node;
    }

    final CodecElements elements = node.getCodecElements();

    final KeyCodec<K> codec = trie.codec;
    final K key = codec.recreateKey(elements.bits, elements.levelsDeep);

    if (key == null) {
      throw new IllegalStateException("Unable to create non-null key with key-codec: " + codec);
    }
    assert getNode(key, trie.root, 0, codec) == node : "Created key must equal original key";

    node.privateKey = key;

    return node;
  }



  /** TrieEntry is a wrapper for a Node that allows it to be exported via public methods */
  protected static final class TrieEntry<K, V> implements Entry<K, V>, Serializable {

    private static final long serialVersionUID = 5057054103095394644L;

    private final AbstractBinaryTrie<K, V> trie; // the backing trie
    private final Node<K, V> node;

    /**
     * Creates an entry wrapper representing a mapping of the Node's key to the Node's value.
     * Wrapped so that getKey will return a resolved key, using the backing Trie.
     *
     * @param node the node to be wrapped in this Entry
     * @param trie the parent trie
     */
    protected TrieEntry(final Node<K, V> node, final AbstractBinaryTrie<K, V> trie) {
      this.trie = trie;
      this.node = node;
    }

    @Override
    public K getKey() {
      return resolveKey(node, trie);
    }

    @Override
    public V getValue() {
      return node.value;
    }

    @Override
    public V setValue(final V value) {
      return node.setValue(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<K, V> e = (Map.Entry<K, V>) o;
      return eq(this.getKey(), e.getKey()) && eq(this.getValue(), e.getValue());
    }

    @Override
    public int hashCode() {
      final K key = this.getKey();
      final V value = this.getValue();
      return (key == null ? 0 : key.hashCode()) ^
          (value == null ? 0 : value.hashCode());
    }

    /**
     * @return a String representation of this map entry
     */
    @Override
    public String toString() {
      return this.getKey() + "=" + this.getValue();
    }
  }



  /**
   * Return a wrapped Node (so that keys are resolved lazily).
   * Returns null if the node is null or the node's value is null (meaning it
   * is an empty intermediate node).
   *
   * @param node the Node to export
   * @param trie the Trie this Node is in
   * @return TrieEntry Map.Entry
   */
  protected static final <K, V> Map.Entry<K, V> exportEntry(final Node<K, V> node,
      final AbstractBinaryTrie<K, V> trie) {
    if (node == null || node.value == null) {
      return null;
    }
    return new TrieEntry<K, V>(node, trie);
  }



  // Utility Methods:

  /**
   * Test two values for equality. Differs from o1.equals(o2) only in
   * that it copes with {@code null} o1 properly.
   *
   * @param o1 Object or null
   * @param o2 Object or null
   * @return true o1 equals o2
   */
  protected static final boolean eq(final Object o1, final Object o2) {
    return (o1 == null ? o2 == null : (o1 == o2 || o1.equals(o2)));
  }



  /**
   * @return {@link KeyCodec} used by this {@link Trie}
   */
  public KeyCodec<K> getCodec() {
    return codec;
  }



  @Override
  public void clear() {
    this.root.value = null;
    this.root.left = null;
    this.root.right = null;
    this.size = 0L;
    ++this.modCount;
  }


  @Override
  public boolean isEmpty() {
    return root.isEmpty();
  }


  @Override
  public int size() {
    return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
  }



  // Modification and Building Methods:

  /**
   * Returns a shallow copy of this {@link AbstractBinaryTrie} instance.
   * (The keys and values themselves are not cloned.)
   *
   * @return a shallow copy of this trie/map
   */
  @Override
  public AbstractBinaryTrie<K, V> clone() {
    return new AbstractBinaryTrie<K, V>(this);
  }

  /**
   * Copies the node structure and node values from {@code otherTrie} onto
   * this Trie. This creates a shallow copy.
   *
   * @param otherTrie AbstractBinaryTrie
   */
  protected void buildFromExisting(final AbstractBinaryTrie<K, V> otherTrie) {

    Node<K, V> myNode = this.root;
    Node<K, V> otherNode = otherTrie.root;

    // Pre-Order tree traversal
    outer: while (otherNode != null) {

      if (otherNode.left != null) {
        otherNode = otherNode.left;
        myNode = myNode.getOrCreateEmpty(true);
        myNode.value = otherNode.value;
        continue;
      }

      if (otherNode.right != null) {
        otherNode = otherNode.right;
        myNode = myNode.getOrCreateEmpty(false);
        myNode.value = otherNode.value;
        continue;
      }

      // We are a leaf node
      while (otherNode.parent != null) {

        if (otherNode == otherNode.parent.left && otherNode.parent.right != null) {
          otherNode = otherNode.parent.right;
          myNode = myNode.parent.getOrCreateEmpty(false);
          myNode.value = otherNode.value;
          continue outer;
        }
        otherNode = otherNode.parent;
        myNode = myNode.parent;
      }
      break;

    }

    this.size = otherTrie.size;
    ++this.modCount;
  }



  @Override
  public V put(final K key, final V value)
      throws ClassCastException, NullPointerException, IllegalArgumentException {

    if (key == null) {
      throw new NullPointerException(getClass().getName()
          + " does not accept null keys: " + key);
    }
    if (value == null) {
      throw new NullPointerException(getClass().getName()
          + " does not accept null values: " + value);
    }

    final int stopDepth = codec.length(key);

    if (stopDepth <= 0) {
      throw new IllegalArgumentException(getClass().getName()
          + " does not accept keys of length <= 0: " + key);
    }

    Node<K, V> subNode = root;
    int i = 0;
    while (true) {
      subNode = subNode.getOrCreateEmpty(codec.isLeft(key, i++));
      if (i == stopDepth) {
        if (subNode.value == null) {
          ++this.size;
        }
        if (subNode.privateKey != null) {
          subNode.privateKey = key;
        }
        ++this.modCount;
        return subNode.setValue(value);
      }
    }
  }

  @Override
  public void putAll(final Map<? extends K, ? extends V> map)
      throws ClassCastException, NullPointerException, IllegalArgumentException {
    for (final Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }



  @Override
  public V remove(final Object key) throws ClassCastException, NullPointerException {
    if (key == null) {
      throw new NullPointerException(getClass().getName()
          + " does not accept null keys: " + key);
    }
    @SuppressWarnings("unchecked")
    final Node<K, V> p = getNode((K) key);
    if (p == null) {
      return null;
    }

    final V oldValue = p.value;
    deleteNode(p);
    return oldValue;
  }

  /**
   * Delete a Node. If a leaf node, this will also delete any empty
   * intermediate parents of the Node, to maintain the contract that all
   * leaf nodes must have a value.
   *
   * @param node Node to delete
   */
  protected void deleteNode(Node<K, V> node) {
    if (node == null || node.value == null) {
      return;
    }

    --this.size;
    ++modCount;
    node.value = null;
    node.privateKey = null;

    while (node.isEmpty() && node.parent != null) {
      if (node.parent.left == node) {
        node.parent.left = null;
      } else {
        node.parent.right = null;
      }
      node = node.parent;
    }
  }



  // Search Methods:

  @Override
  public boolean containsValue(final Object value) throws ClassCastException, NullPointerException {
    if (value == null) {
      throw new NullPointerException(getClass().getName()
          + " does not allow null values: " + value);
    }
    for (Node<K, V> e = firstNode(); e != null; e = successor(e)) {
      if (eq(value, e.value)) {
        return true;
      }
    }
    return false;
  }



  @SuppressWarnings("unchecked")
  @Override
  public boolean containsKey(final Object key) throws ClassCastException, NullPointerException {
    if (key == null) {
      throw new NullPointerException(getClass().getName()
          + " does not allow null keys: " + key);
    }
    return getNode((K) key) != null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public V get(final Object key) throws ClassCastException, NullPointerException {
    if (key == null) {
      throw new NullPointerException(getClass().getName()
          + " does not accept null keys: " + key);
    }
    final Node<K, V> node = getNode((K) key);
    return node == null ? null : node.value;
  }

  /**
   * Return the Node for a given key, or null if not found or the key is null
   *
   * @param key the Key searched for
   * @return Node if found, or null
   */
  protected Node<K, V> getNode(final K key) {
    return getNode(key, root, 0, codec);
  }

  /**
   * Return the Node for a given key, or null if not found or the key is null
   *
   * @param key the Key searched for
   * @param startingNode the node to begin our search underneath (usually root)
   * @param startingIndex the key element index corresponding to the depth of
   *        the startingNode (usually zero)
   * @param codec KeyCodec
   * @return Node if found, or null
   */
  protected static <K, V> Node<K, V> getNode(final K key, final Node<K, V> startingNode,
      int startingIndex, final KeyCodec<K> codec) {

    if (key == null) {
      return null;
    }

    final int stopDepth = codec.length(key);

    if (stopDepth <= 0) {
      throw new IllegalArgumentException(AbstractBinaryTrie.class.getClass().getName()
          + " does not accept keys of length <= 0: " + key);
    }

    // Look up a single record
    Node<K, V> subNode = startingNode;
    while (true) {
      if (codec.isLeft(key, startingIndex++)) {
        subNode = subNode.left;
      } else {
        subNode = subNode.right;
      }

      if (subNode == null) {
        return null;
      }
      if (startingIndex == stopDepth && subNode.value != null) {
        return subNode;
      }
      if (startingIndex >= stopDepth) {
        return null;
      }
    }
  }



  /**
   * @return the first Node in the Trie, or null
   */
  protected Node<K, V> firstNode() {
    return successor(root);
  }



  /**
   * @param node Node to find the successor of (the next node)
   * @return the successor of the specified Node, or null if no such.
   */
  protected static <K, V> Node<K, V> successor(final Node<K, V> node) {
    return successor(node, null);
  }

  /**
   * @param node Node to find the successor of (the next node)
   * @param parentFence Node to force the search for successors to be under
   *        (descendants of), or null if no limit
   * @return the successor of the specified Node, or null if no such.
   */
  protected static <K, V> Node<K, V> successor(Node<K, V> node, final Node<K, V> parentFence) {

    // The fact that nodes do not always have values complicates an otherwise simple
    // Pre-Order parent linkage (stackless, flag-less) tree traversal

    // We can include the parentFence, but we can not go above it
    final Node<K, V> limit = parentFence == null ? null : parentFence.parent;

    outer: while (node != null) {

      if (node.left != null) {
        if (node.left.value == null) {
          node = node.left;
          continue;
        }
        return node.left;
      }

      if (node.right != null) {
        if (node.right.value == null) {
          node = node.right;
          continue;
        }
        return node.right;
      }

      // We are a leaf node
      while (node.parent != null && node.parent != limit) {

        if (node == node.parent.left && node.parent.right != null) {

          if (node.parent.right.value == null) {
            node = node.parent.right;
            continue outer;
          }
          return node.parent.right;
        }
        node = node.parent;
      }
      return null;

    }
    return null;
  }



  /**
   * @return the last Node in the Trie, or null if none
   */
  protected Node<K, V> lastNode() {
    // Rely on the fact that leaf nodes can not be empty
    Node<K, V> parent = root;
    while (parent.right != null || parent.left != null) {
      if (parent.right != null) {
        parent = parent.right;
      } else {
        parent = parent.left;
      }
    }
    return parent == root ? null : parent;
  }



  /**
   * @param node the Node to find the predecessor of (previous)
   * @return the predecessor of the specified Node Entry, or null if no such.
   */
  protected static <K, V> Node<K, V> predecessor(final Node<K, V> node) {
    return predecessor(node, null);
  }

  /**
   * @param node the Node to find the predecessor of (previous)
   * @param parentFence Node to force the search for predecessor to be under
   *        (descendants of), or null if no limit
   * @return the predecessor of the specified Node Entry, or null if no such.
   */
  protected static <K, V> Node<K, V> predecessor(Node<K, V> node, final Node<K, V> parentFence) {

    // The fact that nodes do not always have values complicates an otherwise simple
    // Reverse Post-Order parent linkage (stackless, flag-less) tree traversal

    final Node<K, V> limit = parentFence == null ? null : parentFence.parent;

    while (node != null && node.parent != null && node.parent != limit) {

      // we are on the left, or we have no left sibling, so go up
      if (node == node.parent.left || node.parent.left == null) {

        if (node.parent.value == null) {
          node = node.parent;
          continue;
        }
        return node.parent;
      }

      // we are on the right and have a left sibling
      // so explore the left sibling, all the way down to the right-most child
      node = node.parent.left;
      while (node.right != null || node.left != null) {
        if (node.right != null) {
          node = node.right;
        } else {
          node = node.left;
        }
      }

      if (node.value == null) {
        throw new IllegalStateException("Should not have a leaf node with no value");
      }
      return node;

    }
    return null;
  }



  // Trie Prefix Methods:

  @Override
  public V shortestPrefixOfValue(final K key, final boolean keyInclusive) {
    final Iterator<V> iter = prefixOfValues(key, keyInclusive).iterator();
    return iter.hasNext() ? iter.next() : null;
  }

  @Override
  public V longestPrefixOfValue(final K key, final boolean keyInclusive) {
    final Iterator<V> iter = prefixOfValues(key, keyInclusive).iterator();
    V value = null;
    while (iter.hasNext()) {
      value = iter.next();
    }
    return value;
  }

  @Override
  public Collection<V> prefixOfValues(final K key, final boolean keyInclusive) {
    return prefixValues(key, true, keyInclusive, false);
  }

  @Override
  public Collection<V> prefixedByValues(final K key, final boolean keyInclusive) {
    return prefixValues(key, false, keyInclusive, true);
  }

  protected Collection<V> prefixValues(final K key, final boolean includePrefixOf,
      final boolean keyInclusive, final boolean includePrefixedBy) {
    if (key == null) {
      throw new NullPointerException(getClass().getName() + " does not accept null keys: " + key);
    }
    if (codec.length(key) <= 0) {
      throw new IllegalArgumentException(getClass().getName()
          + " does not accept keys of length <= 0: " + key);
    }
    if (includePrefixOf) {
      return new TriePrefixValues<K, V>(this, null, false, key, keyInclusive);
    }
    return new TriePrefixValues<K, V>(this, key, keyInclusive, null, false);
  }

  @Override
  public Trie<K, V> prefixOfMap(final K key, final boolean keyInclusive) {
    return prefixMap(key, true, keyInclusive, false);
  }

  @Override
  public Trie<K, V> prefixedByMap(final K key, final boolean keyInclusive) {
    return prefixMap(key, false, keyInclusive, true);
  }

  protected Trie<K, V> prefixMap(final K key, final boolean includePrefixOf,
      final boolean keyInclusive, final boolean includePrefixedBy) {
    if (key == null) {
      throw new NullPointerException(getClass().getName() + " does not accept null keys: " + key);
    }
    if (codec.length(key) <= 0) {
      throw new IllegalArgumentException(getClass().getName()
          + " does not accept keys of length <= 0: " + key);
    }

    if (includePrefixOf) {
      return new TriePrefixMap<K, V>(this, null, false, key, keyInclusive);
    }
    return new TriePrefixMap<K, V>(this, key, keyInclusive, null, false);
  }



  /**
   * @param prefix the prefix
   * @param key the key (that we are testing against the prefix)
   * @param includePrefixOfKey if prefix starts with the key and
   *        {@code includePrefixOfKey} is true, this will return true
   * @param keyInclusive if prefix and key are equal, and
   *        {@code keyInclusive} is true, this will return true
   * @param includePrefixedByKey if the key starts with prefix and
   *        {@code includePrefixedByKey} is true, this will return true
   * @param codec KeyCodec
   * @return true if {@code key} is prefixed by {@code prefix}
   */
  protected static <K> boolean isPrefix(final K prefix, final K key,
      final boolean includePrefixOfKey, final boolean keyInclusive,
      final boolean includePrefixedByKey, final KeyCodec<K> codec) {

    if (prefix == null || key == null) {
      return false;
    }

    if (keyInclusive) {
      if (prefix == key || prefix.equals(key)) {
        return true;
      }
    }

    final int prefixDepth = codec.length(prefix);
    final int keyDepth = codec.length(key);

    if ((!includePrefixOfKey && keyDepth < prefixDepth)
        || (!keyInclusive && keyDepth == prefixDepth)
        || (!includePrefixedByKey && keyDepth > prefixDepth)) {
      return false;
    }

    final int minDepth = Math.min(prefixDepth, keyDepth);
    for (int i = 0; i < minDepth; ++i) {
      if (codec.isLeft(prefix, i) != codec.isLeft(key, i)) {
        return false;
      }
    }

    return true;
  }



  // Trie Prefix Iterators:

  /** Iterator for returning prefix keys in ascending order (export before returning them) */
  protected static final class KeyPrefixIterator<K, V>
      extends AbstractPrefixIterator<K, V, K> {

    protected KeyPrefixIterator(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {
      super(trie, mustBePrefixedBy, mustBePrefixedByInclusive, mustBePrefixOf,
          mustBePrefixOfInclusive, false);
    }

    @Override
    public final K next() {
      return resolveKey(nextNode(), trie);
    }
  }

  /** Iterator for returning only prefix values in ascending order */
  protected static final class ValuePrefixIterator<K, V> extends AbstractPrefixIterator<K, V, V> {

    protected ValuePrefixIterator(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {
      super(trie, mustBePrefixedBy, mustBePrefixedByInclusive, mustBePrefixOf,
          mustBePrefixOfInclusive, false);
    }

    @Override
    public final V next() {
      return nextNode().value;
    }
  }

  /** Iterator for returning prefix entries in ascending order (export before returning them) */
  protected static final class EntryPrefixIterator<K, V>
      extends AbstractPrefixIterator<K, V, Map.Entry<K, V>> {

    protected EntryPrefixIterator(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {
      super(trie, mustBePrefixedBy, mustBePrefixedByInclusive, mustBePrefixOf,
          mustBePrefixOfInclusive, false);
    }

    @Override
    public final Map.Entry<K, V> next() {
      return exportEntry(nextNode(), trie);
    }
  }

  /** Iterator for returning prefix Nodes in ascending order (export before returning them) */
  protected static final class NodePrefixIterator<K, V>
      extends AbstractPrefixIterator<K, V, Node<K, V>> {

    protected NodePrefixIterator(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {
      super(trie, mustBePrefixedBy, mustBePrefixedByInclusive, mustBePrefixOf,
          mustBePrefixOfInclusive, false);
    }

    @Override
    public final Node<K, V> next() {
      return nextNode();
    }
  }


  /**
   * Base Prefix Iterator class for extending
   *
   * @param <K> Key
   * @param <V> Value
   * @param <T> Iterator object type
   */
  protected abstract static class AbstractPrefixIterator<K, V, T> implements Iterator<T> {

    protected final AbstractBinaryTrie<K, V> trie; // the backing trie

    protected int expectedModCount;

    // Iterator state
    protected Node<K, V> next;
    protected Node<K, V> lastReturned;
    protected int index = 0;
    protected final int prefixDepth;
    protected final int minDepth;
    protected Node<K, V> upperLimitNode = null;

    // Sub-Trie range keys
    protected final K mustBePrefixedBy; // head/low
    protected final boolean mustBePrefixedByInclusive;
    protected final K mustBePrefixOf; // leaf/high
    protected final boolean mustBePrefixOfInclusive;


    /**
     * Create a new prefix iterator
     *
     * <p>
     * Prefix-Of = all nodes that are direct parents of the mustBePrefixOf Key's node
     * <p>
     * Prefix-By = all children nodes of the mustBePrefixedBy Key's node
     *
     * @param trie the backing trie
     * @param mustBePrefixedBy null or the key that all must be prefixed by
     * @param mustBePrefixedByInclusive true if the mustBePrefixedBy is inclusive
     * @param mustBePrefixOf null or the key that all must be prefixes of
     * @param mustBePrefixOfInclusive true if the mustBePrefixOf is inclusive
     * @param descending false if ascending, true if descending
     */
    protected AbstractPrefixIterator(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive, final boolean descending) {

      this.trie = trie;
      this.expectedModCount = trie.modCount;

      this.mustBePrefixedBy = mustBePrefixedBy;
      this.mustBePrefixedByInclusive = mustBePrefixedByInclusive;
      this.mustBePrefixOf = mustBePrefixOf;
      this.mustBePrefixOfInclusive = mustBePrefixOfInclusive;

      final boolean prefixOf = mustBePrefixOf != null;
      this.prefixDepth = trie.codec.length(prefixOf ? mustBePrefixOf : mustBePrefixedBy);
      // Find the minimum depth for nodes to be returned
      if (mustBePrefixedBy == null) {
        this.minDepth = 1;
      } else {
        this.minDepth = (mustBePrefixedByInclusive ? 0 : 1)
            + (prefixOf ? trie.codec.length(mustBePrefixedBy) : this.prefixDepth);
      }

      if (this.prefixDepth <= 0) {
        throw new IllegalArgumentException(AbstractBinaryTrie.class.getClass().getName()
            + " does not accept keys of length <= 0: "
            + (prefixOf ? mustBePrefixOf : mustBePrefixedBy));
      }

      this.lastReturned = null;
      this.next = getNextPrefixNode(trie.root); // must always start at root

      // If descending, lookup the last node so we can start there
      if (descending) {
        while (hasNext()) {
          nextNode();
        }
        this.next = this.lastReturned;
        this.lastReturned = null;
      }
    }


    /**
     * @param node Node to find the next successor node of
     * @return the successor prefix node, or null if none
     */
    protected Node<K, V> getNextPrefixNode(Node<K, V> node) {
      // Prefix-Of = all nodes that are direct parents of the mustBePrefixOf Key's node
      // Prefix-By = all children nodes of the mustBePrefixedBy Key's node

      final boolean prefixOf = mustBePrefixOf != null;
      final K prefixKey = prefixOf ? mustBePrefixOf : mustBePrefixedBy;

      while (node != null) {

        // Exit early if all further conditions are false
        if ((prefixOf && !mustBePrefixOfInclusive && index + 1 == prefixDepth)
            || (prefixOf && index >= prefixDepth)) {
          return null;
        }

        if (index >= prefixDepth) {
          // Traverse all nodes under the Key (and under upperLimitNode)
          node = successor(node, upperLimitNode);
          ++index;

        } else {
          // Traverse only the path that matches our Key
          if (trie.codec.isLeft(prefixKey, index++)) {
            node = node.left;
          } else {
            node = node.right;
          }
          if (index == prefixDepth) {
            // Force any subsequent tree traversal to be under this node (the mustBePrefixedBy Key)
            upperLimitNode = node;
          }
        }

        if (node == null) {
          return null;
        }

        // If node has a value, and conditions match, return the node
        if (node.value != null && index >= minDepth && subInRange(node)) {
          // If both mustBePrefixOf and mustBePrefixedBy are not null, then we will use
          // mustBePrefixOf as the main key for searching.
          // Because mustBePrefixedBy (if not null) must already be a prefix of mustBePrefixOf,
          // there is no need to check that node is prefixed by it. Instead, we only need to
          // confirm that we are past the depth level of mustBePrefixedBy (minDepth).
          return node;
        }
      }

      return null;
    }


    /**
     * Hook template method for sub-maps to add their own restrictions.
     * But make sure to call {@code super.inRange(node)}.
     *
     * @param node Node to query if in range
     * @return true if the Node is in range for this trie or submap
     */
    protected boolean subInRange(final Node<K, V> node) {
      return true;
    }


    @Override
    public final boolean hasNext() {
      return next != null;
    }

    /**
     * @return the next Node in ascending order
     */
    protected final Node<K, V> nextNode() {
      final Node<K, V> e = next;
      if (e == null) {
        throw new NoSuchElementException();
      }
      if (trie.modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      next = getNextPrefixNode(e);
      lastReturned = e;
      return e;
    }

    @Override
    public final void remove() {
      if (lastReturned == null) {
        throw new IllegalStateException();
      }
      if (trie.modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      trie.deleteNode(lastReturned);
      expectedModCount = trie.modCount;
      lastReturned = null;
    }
  }



  // Trie Prefix Views:

  /** View class for a Set of Keys that are prefixes of a Key. */
  protected static class TriePrefixKeySet<K, V> extends AbstractSet<K>
      implements Set<K> {

    protected final AbstractBinaryTrie<K, V> trie; // the backing trie

    private transient long size = -1L;
    private transient int sizeModCount = -1;

    protected final K mustBePrefixedBy; // head/low
    protected final boolean mustBePrefixedByInclusive;
    protected final K mustBePrefixOf; // leaf/high
    protected final boolean mustBePrefixOfInclusive;


    /**
     * Create a new TriePrefixKeySet View
     *
     * @param trie the backing trie
     * @param mustBePrefixedBy null or the key that all must be prefixed by
     * @param mustBePrefixedByInclusive true if the mustBePrefixedBy is inclusive
     * @param mustBePrefixOf null or the key that all must be prefixes of
     * @param mustBePrefixOfInclusive true if the mustBePrefixOf is inclusive
     */
    protected TriePrefixKeySet(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {

      this.trie = trie;
      this.mustBePrefixedBy = mustBePrefixedBy;
      this.mustBePrefixedByInclusive = mustBePrefixedByInclusive;
      this.mustBePrefixOf = mustBePrefixOf;
      this.mustBePrefixOfInclusive = mustBePrefixOfInclusive;
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyPrefixIterator<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
          mustBePrefixOf, mustBePrefixOfInclusive);
    }

    @Override
    public final int size() {
      if (size == -1L || sizeModCount != trie.modCount) {
        sizeModCount = trie.modCount;
        size = 0L;
        final Iterator<K> i = iterator();
        while (i.hasNext()) {
          ++size;
          i.next();
        }
      }
      return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public final boolean isEmpty() {
      return !iterator().hasNext();
    }

    /**
     * Can be overrided for use with sub-maps, but make sure to call {@code super.inRange(key)}
     *
     * @param key key to query if in range
     * @return true if the key is in range for this trie or submap
     */
    protected boolean inRange(final K key) {
      if (mustBePrefixOf != null &&
          !isPrefix(mustBePrefixOf, key, true, mustBePrefixOfInclusive, false, trie.codec)) {
        return false;
      }
      if (mustBePrefixedBy != null &&
          !isPrefix(mustBePrefixedBy, key, false, mustBePrefixedByInclusive, true, trie.codec)) {
        return false;
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean contains(final Object key) {
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange((K) key)) {
        return false;
      }
      return trie.getNode((K) key) != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean remove(final Object key) {
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange((K) key)) {
        return false;
      }
      final Node<K, V> node = trie.getNode((K) key);
      if (node != null) {
        trie.deleteNode(node);
        return true;
      }
      return false;
    }
  }


  /** View class for a Collection of Values that are prefixes of a Key. */
  protected static class TriePrefixValues<K, V> extends AbstractCollection<V> {

    protected final AbstractBinaryTrie<K, V> trie; // the backing trie

    private transient long size = -1L;
    private transient int sizeModCount = -1;

    protected final K mustBePrefixedBy; // head/low
    protected final boolean mustBePrefixedByInclusive;
    protected final K mustBePrefixOf; // leaf/high
    protected final boolean mustBePrefixOfInclusive;


    /**
     * Create a new TriePrefixValues View
     *
     * @param trie the backing trie
     * @param mustBePrefixedBy null or the key that all must be prefixed by
     * @param mustBePrefixedByInclusive true if the mustBePrefixedBy is inclusive
     * @param mustBePrefixOf null or the key that all must be prefixes of
     * @param mustBePrefixOfInclusive true if the mustBePrefixOf is inclusive
     */
    protected TriePrefixValues(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {

      this.trie = trie;
      this.mustBePrefixedBy = mustBePrefixedBy;
      this.mustBePrefixedByInclusive = mustBePrefixedByInclusive;
      this.mustBePrefixOf = mustBePrefixOf;
      this.mustBePrefixOfInclusive = mustBePrefixOfInclusive;
    }

    @Override
    public Iterator<V> iterator() {
      return new ValuePrefixIterator<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
          mustBePrefixOf, mustBePrefixOfInclusive);
    }

    @Override
    public final int size() {
      if (size == -1L || sizeModCount != trie.modCount) {
        sizeModCount = trie.modCount;
        size = 0L;
        final Iterator<V> i = iterator();
        while (i.hasNext()) {
          ++size;
          i.next();
        }
      }
      return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public final boolean isEmpty() {
      return !iterator().hasNext();
    }

    @Override
    public boolean remove(final Object o) {
      Node<K, V> node = null;
      // only remove values that occur in this sub-trie
      final Iterator<Node<K, V>> iter =
          new NodePrefixIterator<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
              mustBePrefixOf, mustBePrefixOfInclusive);
      while (iter.hasNext()) {
        node = iter.next();
        if (eq(node.value, o)) {
          iter.remove();
          return true;
        }
      }
      return false;
    }
  }


  /** TriePrefixEntrySet prefix entry set view */
  protected static class TriePrefixEntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {

    protected final AbstractBinaryTrie<K, V> trie; // the backing trie

    private transient long size = -1L;
    private transient int sizeModCount = -1;

    protected final K mustBePrefixedBy; // head/low
    protected final boolean mustBePrefixedByInclusive;
    protected final K mustBePrefixOf; // leaf/high
    protected final boolean mustBePrefixOfInclusive;

    /**
     * Create a new TriePrefixEntrySet View
     *
     * @param trie the backing trie
     * @param mustBePrefixedBy null or the key that all must be prefixed by
     * @param mustBePrefixedByInclusive true if the mustBePrefixedBy is inclusive
     * @param mustBePrefixOf null or the key that all must be prefixes of
     * @param mustBePrefixOfInclusive true if the mustBePrefixOf is inclusive
     */
    protected TriePrefixEntrySet(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {

      this.trie = trie;
      this.mustBePrefixedBy = mustBePrefixedBy;
      this.mustBePrefixedByInclusive = mustBePrefixedByInclusive;
      this.mustBePrefixOf = mustBePrefixOf;
      this.mustBePrefixOfInclusive = mustBePrefixOfInclusive;
    }

    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
      return new EntryPrefixIterator<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
          mustBePrefixOf, mustBePrefixOfInclusive);
    }

    @Override
    public final int size() {
      if (size == -1L || sizeModCount != trie.modCount) {
        sizeModCount = trie.modCount;
        size = 0L;
        final Iterator<Map.Entry<K, V>> i = iterator();
        while (i.hasNext()) {
          ++size;
          i.next();
        }
      }
      return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public final boolean isEmpty() {
      return !iterator().hasNext();
    }

    /**
     * Can be overrided for use with sub-maps, but make sure to call {@code super.inRange(key)}
     *
     * @param key key to query if in range
     * @return true if the key is in range for this trie or submap
     */
    protected boolean inRange(final K key) {
      if (mustBePrefixOf != null &&
          !isPrefix(mustBePrefixOf, key, true, mustBePrefixOfInclusive, false, trie.codec)) {
        return false;
      }
      if (mustBePrefixedBy != null &&
          !isPrefix(mustBePrefixedBy, key, false, mustBePrefixedByInclusive, true, trie.codec)) {
        return false;
      }
      return true;
    }

    @Override
    public final boolean contains(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      @SuppressWarnings("unchecked")
      final Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
      final K key = entry.getKey();
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange(key)) {
        return false;
      }
      final Node<K, V> node = trie.getNode(key);
      return node != null && eq(node.value, entry.getValue());
    }

    @Override
    public final boolean remove(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      @SuppressWarnings("unchecked")
      final Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
      final K key = entry.getKey();
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange(key)) {
        return false;
      }
      final Node<K, V> node = trie.getNode(key);
      if (node != null && eq(node.value, entry.getValue())) {
        trie.deleteNode(node);
        return true;
      }
      return false;
    }
  }


  /** TriePrefixMap prefix map view */
  protected static class TriePrefixMap<K, V> extends AbstractMap<K, V>
      implements Trie<K, V>, Serializable {

    private static final long serialVersionUID = 2656477599768768535L;

    /** The backing map. */
    protected final AbstractBinaryTrie<K, V> trie;

    protected final K mustBePrefixedBy; // head/low
    protected final boolean mustBePrefixedByInclusive;
    protected final K mustBePrefixOf; // leaf/high
    protected final boolean mustBePrefixOfInclusive;

    private transient long size = -1L;
    private transient int sizeModCount = -1;

    protected transient Set<Map.Entry<K, V>> entrySet = null;
    protected transient Set<K> keySet = null;
    protected transient Collection<V> values = null;

    /**
     * Create a new TriePrefixMap View
     *
     * @param trie the backing trie
     * @param mustBePrefixedBy null or the key that all must be prefixed by
     * @param mustBePrefixedByInclusive true if the mustBePrefixedBy is inclusive
     * @param mustBePrefixOf null or the key that all must be prefixes of
     * @param mustBePrefixOfInclusive true if the mustBePrefixOf is inclusive
     */
    protected TriePrefixMap(final AbstractBinaryTrie<K, V> trie,
        final K mustBePrefixedBy, final boolean mustBePrefixedByInclusive,
        final K mustBePrefixOf, final boolean mustBePrefixOfInclusive) {

      this.trie = trie;
      this.mustBePrefixedBy = mustBePrefixedBy;
      this.mustBePrefixedByInclusive = mustBePrefixedByInclusive;
      this.mustBePrefixOf = mustBePrefixOf;
      this.mustBePrefixOfInclusive = mustBePrefixOfInclusive;
    }

    @Override
    public final int size() {
      if (size == -1L || sizeModCount != trie.modCount) {
        sizeModCount = trie.modCount;
        size = 0L;
        final Iterator<Map.Entry<K, V>> i = entrySet().iterator();
        while (i.hasNext()) {
          ++size;
          i.next();
        }
      }
      return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public final boolean isEmpty() {
      return !entrySet().iterator().hasNext();
    }

    @Override
    public V put(final K key, final V value)
        throws ClassCastException, NullPointerException, IllegalArgumentException {
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange(key, false)) {
        throw new IllegalArgumentException("key out of range: " + key);
      }
      return trie.put(key, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V remove(final Object key) throws ClassCastException, NullPointerException {
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not accept null keys: " + key);
      }
      if (!inRange((K) key, false)) {
        return null;
      }
      return trie.remove(key);
    }

    @Override
    public boolean containsKey(final Object key) {
      return get(key) != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(final Object key) {
      if (key == null) {
        throw new NullPointerException(getClass().getName()
            + " does not allow null keys: " + key);
      }
      if (!inRange((K) key, false)) {
        return null;
      }
      return trie.get(key);
    }


    @Override
    public V shortestPrefixOfValue(final K key, final boolean keyInclusive) {
      final Iterator<V> iter = prefixOfValues(key, keyInclusive).iterator();
      return iter.hasNext() ? iter.next() : null;
    }

    @Override
    public V longestPrefixOfValue(final K key, final boolean keyInclusive) {
      final Iterator<V> iter = prefixOfValues(key, keyInclusive).iterator();
      V value = null;
      while (iter.hasNext()) {
        value = iter.next();
      }
      return value;
    }

    @Override
    public Collection<V> prefixOfValues(final K key, final boolean keyInclusive) {
      return prefixValues(key, true, keyInclusive, false);
    }

    @Override
    public Collection<V> prefixedByValues(final K key, final boolean keyInclusive) {
      return prefixValues(key, false, keyInclusive, true);
    }

    protected Collection<V> prefixValues(final K key, final boolean includePrefixOf,
        final boolean keyInclusive, final boolean includePrefixedBy) {
      checkKeyValidAndInRange(key, !keyInclusive);

      if (includePrefixOf) {
        // Wants prefix of, create with new prefix of key, pass along current mustBePrefixedBy
        return new TriePrefixValues<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive, key,
            keyInclusive);

      } else {
        // Wants prefixed by, create with new prefixed by key, pass current mustBePrefixOf
        return new TriePrefixValues<K, V>(trie, key, keyInclusive, mustBePrefixOf,
            mustBePrefixOfInclusive);
      }
    }

    @Override
    public Trie<K, V> prefixOfMap(final K key, final boolean keyInclusive) {
      return prefixMap(key, true, keyInclusive, false);
    }

    @Override
    public Trie<K, V> prefixedByMap(final K key, final boolean keyInclusive) {
      return prefixMap(key, false, keyInclusive, true);
    }

    protected Trie<K, V> prefixMap(final K key, final boolean includePrefixOf,
        final boolean keyInclusive, final boolean includePrefixedBy) {
      checkKeyValidAndInRange(key, !keyInclusive);

      if (includePrefixOf) {
        // Wants prefix of, create with new prefix of key, pass along current mustBePrefixedBy
        return new TriePrefixMap<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive, key,
            keyInclusive);

      } else {
        // Wants prefixed by, create with new prefixed by key, pass current mustBePrefixOf
        return new TriePrefixMap<K, V>(trie, key, keyInclusive, mustBePrefixOf,
            mustBePrefixOfInclusive);
      }
    }


    /**
     * Can be overrided for use with sub-maps, but make sure to call {@code super.inRange(key)}
     *
     * @param key key to query if in range
     * @param forceInclusive true if the prefixKey and key may be equal
     * @return true if the key is in range for this trie or submap
     */
    protected boolean inRange(final K key, final boolean forceInclusive) {
      if (mustBePrefixOf != null &&
          !isPrefix(mustBePrefixOf, key, true, mustBePrefixOfInclusive || forceInclusive, false,
              trie.codec)) {
        return false;
      }
      if (mustBePrefixedBy != null &&
          !isPrefix(mustBePrefixedBy, key, false, mustBePrefixedByInclusive || forceInclusive, true,
              trie.codec)) {
        return false;
      }
      return true;
    }

    /**
     * Check if key is valid and throw an exception if not valid
     *
     * @param key key to query if valid and in range
     * @param forceInclusive true if the prefixKey and key may be equal
     */
    protected void checkKeyValidAndInRange(final K key, final boolean forceInclusive) {
      if (key == null) {
        throw new NullPointerException(getClass().getName() + " does not accept null keys: " + key);
      }
      if (trie.codec.length(key) <= 0) {
        throw new IllegalArgumentException(getClass().getName()
            + " does not accept keys of length <= 0: " + key);
      }
      // !keyInclusive because if we want to make a non-inclusive map,
      // the range check should allow the key to match a non-inclusive prefix
      if (!inRange(key, forceInclusive)) {
        throw new IllegalArgumentException("key out of range: " + key);
      }
    }


    @Override
    public Set<K> keySet() {
      final Set<K> ks = keySet;
      return (ks != null) ? ks : (keySet =
          new TriePrefixKeySet<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
              mustBePrefixOf, mustBePrefixOfInclusive));
    }

    @Override
    public Collection<V> values() {
      final Collection<V> vs = values;
      return (vs != null) ? vs : (values =
          new TriePrefixValues<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
              mustBePrefixOf, mustBePrefixOfInclusive));
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
      final Set<Map.Entry<K, V>> es = entrySet;
      return (es != null) ? es : (entrySet =
          new TriePrefixEntrySet<K, V>(trie, mustBePrefixedBy, mustBePrefixedByInclusive,
              mustBePrefixOf, mustBePrefixOfInclusive));
    }
  }



  // Map Iterators:

  /** Iterator for returning exported Map.Entry views of Nodes in ascending order */
  protected static final class EntryIterator<K, V>
      extends AbstractNodeIterator<K, V, Map.Entry<K, V>> {

    protected EntryIterator(final AbstractBinaryTrie<K, V> map) {
      super(map, map.firstNode());
    }

    @Override
    public final Map.Entry<K, V> next() {
      return exportEntry(nextNode(), m);
    }
  }

  /** Iterator for returning only values in ascending order */
  protected static final class ValueIterator<K, V> extends AbstractNodeIterator<K, V, V> {

    protected ValueIterator(final AbstractBinaryTrie<K, V> map) {
      super(map, map.firstNode());
    }

    @Override
    public final V next() {
      return nextNode().value;
    }
  }

  /**
   * @return Iterator returning resolved keys in ascending order
   */
  protected final Iterator<K> keyIterator() {
    return new KeyIterator<K, V>(this);
  }

  /** Iterator for returning only resolved keys in ascending order */
  protected static final class KeyIterator<K, V> extends AbstractNodeIterator<K, V, K> {

    protected KeyIterator(final AbstractBinaryTrie<K, V> map) {
      super(map, map.firstNode());
    }

    @Override
    public final K next() {
      return resolveKey(nextNode(), m);
    }
  }



  /**
   * Base Node Iterator for extending
   *
   * @param <K> Key
   * @param <V> Value
   * @param <T> Iterator object type
   */
  protected abstract static class AbstractNodeIterator<K, V, T> implements Iterator<T> {

    protected final AbstractBinaryTrie<K, V> m; // the backing map

    protected Node<K, V> next;
    protected Node<K, V> lastReturned;
    protected int expectedModCount;

    /**
     * Create a new AbstractEntryIterator
     *
     * @param map the backing trie
     * @param first the first Node returned by nextNode or prevNode
     */
    protected AbstractNodeIterator(final AbstractBinaryTrie<K, V> map, final Node<K, V> first) {
      this.m = map;
      expectedModCount = m.modCount;
      lastReturned = null;
      next = first;
    }

    @Override
    public final boolean hasNext() {
      return next != null;
    }

    /**
     * @return the successor Node (ascending order) or null
     */
    protected final Node<K, V> nextNode() {
      final Node<K, V> e = next;
      if (e == null) {
        throw new NoSuchElementException();
      }
      if (m.modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      next = successor(e);
      lastReturned = e;
      return e;
    }

    @Override
    public final void remove() {
      if (lastReturned == null) {
        throw new IllegalStateException();
      }
      if (m.modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      m.deleteNode(lastReturned);
      expectedModCount = m.modCount;
      lastReturned = null;
    }
  }



  // Map Views:

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks != null) ? ks : (keySet = new TrieKeySet<K>(this));
  }


  /** KeySet View Set of Keys */
  protected static final class TrieKeySet<K> extends AbstractSet<K>
      implements Set<K> {

    protected final AbstractBinaryTrie<K, ? extends Object> m;

    /**
     * Create a new TrieKeySet view
     *
     * @param map the backing AbstractBinaryTrie
     */
    protected TrieKeySet(final AbstractBinaryTrie<K, ? extends Object> map) {
      m = map;
    }

    @Override
    public final Iterator<K> iterator() {
      return m.keyIterator();
    }

    @Override
    public final int size() {
      return m.size();
    }

    @Override
    public final boolean isEmpty() {
      return m.isEmpty();
    }

    @Override
    public final boolean contains(final Object o) {
      return m.containsKey(o);
    }

    @Override
    public final void clear() {
      m.clear();
    }

    @Override
    public final boolean remove(final Object o) {
      return m.remove(o) != null;
    }

  }



  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs != null) ? vs : (values = new TrieValues<K, V>(this));
  }

  /** TrieValues View Collection of Values */
  protected static final class TrieValues<K, V> extends AbstractCollection<V> {

    protected final AbstractBinaryTrie<K, V> m; // the backing map

    /**
     * Create a new TrieValues view
     *
     * @param map the backing AbstractBinaryTrie
     */
    protected TrieValues(final AbstractBinaryTrie<K, V> map) {
      this.m = map;
    }

    @Override
    public final Iterator<V> iterator() {
      return new ValueIterator<K, V>(m);
    }

    @Override
    public final int size() {
      return m.size();
    }

    @Override
    public final boolean isEmpty() {
      return m.isEmpty();
    }

    @Override
    public final boolean contains(final Object o) {
      return m.containsValue(o);
    }

    @Override
    public final boolean remove(final Object o) {
      for (Node<K, V> e = m.firstNode(); e != null; e = successor(e)) {
        if (eq(e.value, o)) {
          m.deleteNode(e);
          return true;
        }
      }
      return false;
    }

    @Override
    public final void clear() {
      m.clear();
    }
  }



  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    final Set<Map.Entry<K, V>> es = entrySet;
    return (es != null) ? es : (entrySet = new TrieEntrySet());
  }

  /** TrieEntrySet View Set of Map.Entry key-value pairs */
  protected final class TrieEntrySet extends AbstractSet<Map.Entry<K, V>> {

    @Override
    public final Iterator<Map.Entry<K, V>> iterator() {
      return new EntryIterator<K, V>(AbstractBinaryTrie.this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean contains(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
      final V value = entry.getValue();
      final Node<K, V> p = getNode(entry.getKey());
      return p != null && eq(p.value, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean remove(final Object o) {
      if (!(o instanceof Map.Entry)) {
        return false;
      }
      final Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
      final V value = entry.getValue();
      final Node<K, V> p = getNode(entry.getKey());
      if (p != null && eq(p.value, value)) {
        deleteNode(p);
        return true;
      }
      return false;
    }

    @Override
    public final int size() {
      return AbstractBinaryTrie.this.size();
    }

    @Override
    public final void clear() {
      AbstractBinaryTrie.this.clear();
    }
  }



  // Object Methods:

  @Override
  public int hashCode() {
    // To stay compatible with Map interface, we are equal to any map with the same mappings
    int h = 0;
    for (Node<K, V> node = this.firstNode(); node != null; node = successor(node)) {
      final V value = node.value;
      final K key = resolveKey(node, this);
      // Map.hashCode compatibility
      h += (key == null ? 0 : key.hashCode()) ^
          (value == null ? 0 : value.hashCode());
    }
    return h;
  }



  @SuppressWarnings("unchecked")
  @Override
  public boolean equals(final Object o) {

    if (o == this) {
      return true;
    }

    if (o instanceof AbstractBinaryTrie) {
      // We are comparing against another AbstractBinaryTrie, so we can take shortcuts
      final AbstractBinaryTrie<K, V> t = (AbstractBinaryTrie<K, V>) o;
      if (t.size() != size()) {
        return false;
      }
      return compareAllNodes(this.root, t.root);
    }

    if (o instanceof Map) {
      final Map<K, V> m = (Map<K, V>) o;
      if (m.size() != size()) {
        return false;
      }
      // To stay compatible with Map interface, we are equal to any map with the same mappings
      try {
        for (Node<K, V> node = this.firstNode(); node != null; node = successor(node)) {
          final V value = node.value;
          final K key = resolveKey(node, this);
          if (value == null) {
            if (!(m.get(key) == null && m.containsKey(key))) {
              return false;
            }
          } else {
            if (!value.equals(m.get(key))) {
              return false;
            }
          }
        }
      } catch (final ClassCastException unused) {
        return false;
      } catch (final NullPointerException unused) {
        return false;
      }
      return true;
    }

    return false;
  }


  /**
   * Compares one Node to another Node. Does not recursively compare children,
   * only tests the existence of children. Assumes both nodes are in the same
   * position in the structure (and therefore have equal keys).
   *
   * @param myNode Node (or null)
   * @param otherNode Node (or null)
   * @return false if either node has children the other lacks, and false
   *         if the values are not equal
   */
  protected static final <K, V> boolean compareNodeAndExistenceOfChildren(
      final AbstractBinaryTrie.Node<K, V> myNode,
      final AbstractBinaryTrie.Node<K, V> otherNode) {

    if (myNode == null && otherNode == null) {
      return true;
    }

    if ((myNode == null && otherNode != null)
        || (myNode != null && otherNode == null)) {
      return false;
    }

    if ((myNode.left == null && otherNode.left != null)
        || (myNode.left != null && otherNode.left == null)) {
      return false;
    }

    if ((myNode.right == null && otherNode.right != null)
        || (myNode.right != null && otherNode.right == null)) {
      return false;
    }

    if (!eq(myNode.value, otherNode.value)) {
      return false;
    }

    return true;
  }

  /**
   * Compare the node's values and node structure of two tries, starting at
   * two Node at the same place in their respective structures, and then
   * walking both trie's.
   *
   * @param myNode Node from one trie (usually root)
   * @param otherNode Node from the other trie (usually root)
   * @return true if the trie's are equal because the node structures are equal
   *         and the nodes at the same spot in their respective structures have
   *         equal values
   */
  protected static final <K, V> boolean compareAllNodes(Node<K, V> myNode, Node<K, V> otherNode) {

    // Pre-Order tree traversal
    outer: while (otherNode != null) {

      if (otherNode.left != null) {
        otherNode = otherNode.left;
        myNode = myNode.left;
        if (!compareNodeAndExistenceOfChildren(myNode, otherNode)) {
          return false;
        }
        continue;
      }

      if (otherNode.right != null) {
        otherNode = otherNode.right;
        myNode = myNode.right;
        if (!compareNodeAndExistenceOfChildren(myNode, otherNode)) {
          return false;
        }
        continue;
      }

      // We are a leaf node
      while (otherNode.parent != null) {

        if (otherNode == otherNode.parent.left && otherNode.parent.right != null) {
          otherNode = otherNode.parent.right;
          myNode = myNode.parent.right;
          if (!compareNodeAndExistenceOfChildren(myNode, otherNode)) {
            return false;
          }
          continue outer;
        }
        otherNode = otherNode.parent;
        myNode = myNode.parent;
      }
      break;

    }

    return compareNodeAndExistenceOfChildren(myNode, otherNode);
  }



  @Override
  public String toString() {
    // maybe create a ascii diagram for the tree structure?
    if (this.isEmpty()) {
      return "{}";
    }

    final StringBuilder sb = new StringBuilder();
    sb.append('{');

    for (Node<K, V> node = this.firstNode(); node != null;) {
      final V value = node.value;
      final K key = resolveKey(node, this);
      sb.append(key == this ? "(this Map)" : key);
      sb.append('=');
      sb.append(value == this ? "(this Map)" : value);
      if ((node = successor(node)) == null) {
        break;
      }
      sb.append(',').append(' ');
    }
    return sb.append('}').toString();
  }



  /**
   * Write out this trie to the output stream.
   * First write the default object,
   * Second write out size,
   * Third write out alternating key-value pairs.
   *
   * @param s ObjectOutputStream
   * @throws IOException
   */
  private final void writeObject(final ObjectOutputStream s) throws IOException {
    // Write out the codec and any hidden stuff
    s.defaultWriteObject();

    // Write out size (number of Mappings)
    s.writeLong(size);

    // If Write out keys and values (alternating)
    for (Node<K, V> node = this.firstNode(); node != null; node = successor(node)) {
      s.writeObject(resolveKey(node, this));
      s.writeObject(node.value);
    }
  }

  /**
   * Read in this trie from the input stream.
   * First read the default object,
   * Second read in size,
   * Third, read in and put alternating key-value pairs.
   *
   * @param s ObjectInputStream
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @SuppressWarnings("unchecked")
  private final void readObject(final ObjectInputStream s)
      throws IOException, ClassNotFoundException {
    // Read in the codec and any hidden stuff
    s.defaultReadObject();

    // Read in size (number of Mappings)
    final long originalSize = s.readLong();

    // Read in keys and values (alternating)
    this.root = new Node<K, V>(null);
    for (int i = 0; i < originalSize; ++i) {
      final K key = (K) s.readObject();
      final V value = (V) s.readObject();
      this.put(key, value);
    }
    assert (this.size == originalSize);
  }


}
