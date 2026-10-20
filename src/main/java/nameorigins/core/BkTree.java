package nameorigins.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Burkhard-Keller tree: finds every item within distance r of a query without comparing against all items.
 *
 * Each child edge is labelled with the distance d(node, child). By the triangle inequality, an item within r of
 * the query can only sit under a child whose label is in [d(query, node) - r, d(query, node) + r], so whole
 * subtrees outside that window are skipped. Children are kept in a small array indexed by label (labels are
 * small integers for edit distance), so no per-edge map objects are allocated.
 *
 * Generic over the item type: the metric is supplied by the caller.
 */
public final class BkTree<T> {

    /** A distance function that obeys the triangle inequality. limit lets it stop early (see EditDistance). */
    public interface Metric<T> {
        int distance(T a, T b, int limit);
    }

    private static final class Node<T> {
        final T item;
        Node<T>[] children;

        Node(T item) {
            this.item = item;
        }
    }

    private final Metric<T> metric;
    private Node<T> root;
    private int size;
    private int lastVisited;

    public BkTree(Metric<T> metric) {
        this.metric = metric;
    }

    public void add(T item) {
        size++;
        if (root == null) {
            root = new Node<>(item);
            return;
        }
        Node<T> node = root;
        while (true) {
            int d = metric.distance(item, node.item, Integer.MAX_VALUE / 2);
            if (d == 0) {
                size--;
                return; // already present
            }
            if (node.children == null || node.children.length <= d) {
                node.children = grow(node.children, d + 1);
            }
            Node<T> next = node.children[d];
            if (next == null) {
                node.children[d] = new Node<>(item);
                return;
            }
            node = next;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Node<T>[] grow(Node<T>[] old, int min) {
        Node<T>[] bigger = (Node<T>[]) new Node[Math.max(min, old == null ? 4 : old.length * 2)];
        if (old != null) {
            System.arraycopy(old, 0, bigger, 0, old.length);
        }
        return bigger;
    }

    /** Every item within distance r, paired with its distance, in no particular order. */
    public List<Scored<T>> within(T query, int r) {
        List<Scored<T>> out = new ArrayList<>();
        lastVisited = 0;
        if (root == null) {
            return out;
        }
        List<Node<T>> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Node<T> node = stack.remove(stack.size() - 1);
            lastVisited++;
            // The exact distance is needed to choose children, so the limit is generous: r plus the largest label.
            int cap = node.children == null ? r : r + node.children.length;
            int d = metric.distance(query, node.item, cap);
            if (d <= r) {
                out.add(new Scored<>(node.item, d));
            }
            if (node.children == null) {
                continue;
            }
            int lo = Math.max(1, d - r);
            int hi = Math.min(node.children.length - 1, d + r);
            for (int k = lo; k <= hi; k++) {
                if (node.children[k] != null) {
                    stack.add(node.children[k]);
                }
            }
        }
        return out;
    }

    public int size() {
        return size;
    }

    /** How many nodes the last {@link #within} call compared against: the measure of how much was skipped. */
    public int lastVisited() {
        return lastVisited;
    }
}
