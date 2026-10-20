package nameorigins.core;

/**
 * Disjoint sets over 0..n-1. Groups spelling variants: if Mohammed ~ Muhammad and Muhammad ~ Mohamed, all
 * three end up in one group even though no single source links Mohammed to Mohamed.
 *
 * Path compression (every find points nodes straight at the root) plus union by rank (attach the shorter tree
 * under the taller) make each operation O(alpha(n)), which is at most 4 for any realistic n.
 */
public final class UnionFind {
    private final int[] parent;
    private final byte[] rank;

    public UnionFind(int n) {
        parent = new int[n];
        rank = new byte[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
    }

    public int find(int x) {
        int root = x;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[x] != root) {
            int next = parent[x];
            parent[x] = root;
            x = next;
        }
        return root;
    }

    public void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return;
        }
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb;
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra;
        } else {
            parent[rb] = ra;
            rank[ra]++;
        }
    }

    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }
}
