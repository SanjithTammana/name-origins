package nameorigins.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every known name, held in parallel arrays (one slot per name) rather than one object per name: 56k names
 * as a few arrays is far lighter in the browser than 56k objects with their headers and pointers.
 *
 * Each question gets the structure that answers it (see PROJECT.md, "Data structures"):
 *   exact name     -> binary search over the sorted keys          O(L log N)
 *   prefix         -> the same binary search, then a scan         O(L log N + k)
 *   sounds-like    -> HashMap sound key -> slots                  O(L) + bucket
 *   variant group  -> group id per slot (union-find run offline)  O(1)
 *   near spelling  -> BK-tree over keys                           skips most of N
 *
 * Load time matters more than lookup time here (measured in the browser: building every index at load took
 * 1.2 s on a desktop). So loading only fills the arrays; the sound-key maps, variant groups and BK-tree are
 * built the first time a query needs them, which happens only for names that are not found as typed. Exact
 * lookup uses binary search on the keys, already sorted in the file, so it needs no hash map at all.
 *
 * File format (directory.tsv), sorted by key (String.compareTo order): key, display (empty when it equals
 * Text.defaultDisplay(key)), group, state, where state is
 * 2 = has an origin chain, 1 = has entries but no chain, 0 = a recorded spelling with no entry of its own.
 */
public final class NameDirectory {
    private final String[] keys;
    private final String[] display;
    private final int[] group;
    private final boolean[] hasOrigin;
    private final boolean[] hasEntries;
    private Map<String, int[]> byStrong;
    private Map<String, int[]> bySkeleton;
    private Map<Integer, int[]> groups;
    private BkTree<String> tree;

    private NameDirectory(int n) {
        keys = new String[n];
        display = new String[n];
        group = new int[n];
        hasOrigin = new boolean[n];
        hasEntries = new boolean[n];
    }

    /** One pass over the text with indexOf; no per-line lists or arrays. */
    public static NameDirectory parse(String tsv) {
        int n = 0;
        for (int i = tsv.indexOf('\n'); i >= 0; i = tsv.indexOf('\n', i + 1)) {
            n++;
        }
        if (!tsv.isEmpty() && tsv.charAt(tsv.length() - 1) != '\n') {
            n++;
        }
        NameDirectory d = new NameDirectory(n);
        int row = 0;
        int start = 0;
        while (start < tsv.length() && row < n) {
            int end = tsv.indexOf('\n', start);
            if (end < 0) {
                end = tsv.length();
            }
            int t1 = tsv.indexOf('\t', start);
            int t2 = tsv.indexOf('\t', t1 + 1);
            int t3 = tsv.indexOf('\t', t2 + 1);
            d.keys[row] = tsv.substring(start, t1);
            // empty display = the key with default capitals (most rows): no string to cut out here
            d.display[row] = t2 == t1 + 1 ? null : tsv.substring(t1 + 1, t2);
            int g = 0;
            for (int i = t2 + 1; i < t3; i++) {
                g = g * 10 + (tsv.charAt(i) - '0');
            }
            d.group[row] = g;
            char state = tsv.charAt(t3 + 1);
            d.hasOrigin[row] = state == '2';
            d.hasEntries[row] = state != '0';
            row++;
            start = end + 1;
        }
        return d;
    }

    public int size() {
        return keys.length;
    }

    /** Slot of a folded name, or -1. Binary search: the file is sorted by key. */
    public int slot(String folded) {
        int lo = 0;
        int hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[mid].compareTo(folded);
            if (c == 0) {
                return mid;
            }
            if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    public String key(int slot) {
        return keys[slot];
    }

    public String display(int slot) {
        String d = display[slot];
        return d != null ? d : Text.defaultDisplay(keys[slot]);
    }

    public boolean hasOrigin(int slot) {
        return hasOrigin[slot];
    }

    /** True when the name has at least one entry to show (with or without an origin chain). */
    public boolean hasEntries(int slot) {
        return hasEntries[slot];
    }

    public int[] soundsLike(String folded) {
        buildSoundIndexStep(Integer.MAX_VALUE);
        int[] a = byStrong.get(SoundKey.strong(folded));
        return a == null ? new int[0] : a;
    }

    public int[] skeletonLike(String folded) {
        buildSoundIndexStep(Integer.MAX_VALUE);
        int[] a = bySkeleton.get(SoundKey.skeleton(folded));
        return a == null ? new int[0] : a;
    }

    private Map<String, List<Integer>> pendingStrong;
    private Map<String, List<Integer>> pendingSkeleton;
    private int soundDone;

    /**
     * Adds up to maxRows names to the sound-key maps; true once complete. Called in small slices from timer ticks
     * so building it for 150k names never freezes the page (one go measured 1.2 s); a query that needs it before
     * it is done finishes it on the spot.
     */
    public boolean buildSoundIndexStep(int maxRows) {
        if (byStrong != null) {
            return true;
        }
        if (pendingStrong == null) {
            pendingStrong = new HashMap<>();
            pendingSkeleton = new HashMap<>();
        }
        int end = (int) Math.min(keys.length, (long) soundDone + maxRows);
        for (int i = soundDone; i < end; i++) {
            if (Text.isLatinWord(keys[i])) {
                String strong = SoundKey.strong(keys[i]);
                pendingStrong.computeIfAbsent(strong, x -> new ArrayList<>()).add(i);
                pendingSkeleton.computeIfAbsent(SoundKey.skeletonOfStrong(strong), x -> new ArrayList<>()).add(i);
            }
        }
        soundDone = end;
        if (soundDone < keys.length) {
            return false;
        }
        byStrong = toArrays(pendingStrong);
        bySkeleton = toArrays(pendingSkeleton);
        pendingStrong = null;
        pendingSkeleton = null;
        return true;
    }

    private BkTree<String> pendingTree;
    private int treeDone;

    /**
     * Adds up to maxRows names to the BK-tree; true once complete. Only names with an origin chain go in: they are
     * what suggestions show, and leaving out the ~90k Wikidata-only names cut the build from 5.6 s.
     */
    public boolean buildTreeStep(int maxRows) {
        if (tree != null) {
            return true;
        }
        if (pendingTree == null) {
            EditDistance ed = new EditDistance();
            pendingTree = new BkTree<>(ed::within);
        }
        int end = (int) Math.min(keys.length, (long) treeDone + maxRows);
        for (int i = treeDone; i < end; i++) {
            if (hasOrigin[i] && Text.isLatinWord(keys[i])) {
                pendingTree.add(keys[i]);
            }
        }
        treeDone = end;
        if (treeDone < keys.length) {
            return false;
        }
        tree = pendingTree;
        pendingTree = null;
        return true;
    }

    private static Map<String, int[]> toArrays(Map<String, List<Integer>> m) {
        Map<String, int[]> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
            List<Integer> l = e.getValue();
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = l.get(i);
            }
            out.put(e.getKey(), a);
        }
        return out;
    }

    /** Other slots in the same variant group (excluding the slot itself). */
    public int[] variantsOf(int slot) {
        if (groups == null) {
            Map<Integer, List<Integer>> g = new HashMap<>();
            for (int i = 0; i < group.length; i++) {
                g.computeIfAbsent(group[i], x -> new ArrayList<>()).add(i);
            }
            groups = new HashMap<>();
            for (Map.Entry<Integer, List<Integer>> e : g.entrySet()) {
                if (e.getValue().size() > 1) {
                    int[] a = new int[e.getValue().size()];
                    for (int i = 0; i < a.length; i++) {
                        a[i] = e.getValue().get(i);
                    }
                    groups.put(e.getKey(), a);
                }
            }
        }
        int[] members = groups.get(group[slot]);
        if (members == null) {
            return new int[0];
        }
        int[] out = new int[members.length - 1];
        int j = 0;
        for (int m : members) {
            if (m != slot) {
                out[j++] = m;
            }
        }
        return out;
    }

    /** Up to k slots whose key starts with the folded prefix, in key order. */
    public List<Integer> withPrefix(String prefix, int k) {
        List<Integer> out = new ArrayList<>();
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int i = lo; i < keys.length && out.size() < k && keys[i].startsWith(prefix); i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * Recorded names with an origin chain within edit distance r of the folded name, nearest first.
     * Finishes the BK-tree first if warm-up has not.
     */
    public List<Scored<Integer>> nearest(String folded, int r, int k) {
        buildTreeStep(Integer.MAX_VALUE);
        List<Scored<Integer>> out = new ArrayList<>();
        for (Scored<String> s : tree.within(folded, r)) {
            if (s.score() > 0) {
                out.add(new Scored<>(slot(s.item()), s.score()));
            }
        }
        out.sort((a, b) -> {
            int c = Double.compare(a.score(), b.score());
            if (c != 0) {
                return c;
            }
            c = Boolean.compare(hasOrigin[b.item()], hasOrigin[a.item()]);
            return c != 0 ? c : keys[a.item()].compareTo(keys[b.item()]);
        });
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    /** Nodes compared in the last nearest() call; used by tests and the complexity write-up. */
    public int lastTreeVisits() {
        return tree == null ? 0 : tree.lastVisited();
    }
}
