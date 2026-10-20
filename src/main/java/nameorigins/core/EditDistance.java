package nameorigins.core;

/**
 * Levenshtein distance that only needs to know "is it within the limit, and if so how far".
 *
 * Plain Levenshtein fills a (|a|+1) x (|b|+1) table: O(L^2). Two observations cut that down:
 *  1. Cells further than `limit` from the diagonal can never lead to a result <= limit, so only a band of
 *     width 2*limit+1 is computed: O(limit * L).
 *  2. Each row's minimum never decreases going down, so once it exceeds the limit we can stop early.
 * The two rows are reused across calls, so a search over thousands of names allocates nothing.
 * Not thread-safe: one instance per thread (the browser has one).
 */
public final class EditDistance {
    private int[] prev = new int[32];
    private int[] cur = new int[32];

    /** Returns the distance if it is <= limit, otherwise limit + 1. */
    public int within(String a, String b, int limit) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > limit) {
            return limit + 1;
        }
        if (prev.length < m + 1) {
            prev = new int[m + 1];
            cur = new int[m + 1];
        }
        int big = limit + 1;
        for (int j = 0; j <= m; j++) {
            prev[j] = j <= limit ? j : big;
        }
        for (int i = 1; i <= n; i++) {
            int lo = Math.max(1, i - limit);
            int hi = Math.min(m, i + limit);
            cur[0] = i <= limit ? i : big;
            if (lo > 1) {
                cur[lo - 1] = big;
            }
            int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = lo; j <= hi; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (v > big) {
                    v = big;
                }
                cur[j] = v;
                if (v < rowMin) {
                    rowMin = v;
                }
            }
            if (hi < m) {
                cur[hi + 1] = big;
            }
            if (rowMin > limit) {
                return big;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return Math.min(prev[m], big);
    }
}
