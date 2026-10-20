package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Randomized checks against plain reference implementations. Fixed seeds, so a failure always reproduces. */
class PropertyTest {

    private static String word(Random rnd, String alphabet, int maxLen) {
        int len = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Textbook full-table Levenshtein. */
    private static int levenshtein(String a, String b) {
        int[][] t = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            t[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            t[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                t[i][j] = Math.min(Math.min(t[i - 1][j] + 1, t[i][j - 1] + 1), t[i - 1][j - 1] + cost);
            }
        }
        return t[a.length()][b.length()];
    }

    @Test
    void bandedEditDistanceAgreesWithTheFullTable() {
        Random rnd = new Random(1);
        EditDistance ed = new EditDistance(); // one instance throughout, as in the page: reused rows must not leak
        int[] limits = {0, 1, 2, 3, 4, 5, 50, Integer.MAX_VALUE / 2, Integer.MAX_VALUE};
        for (int n = 0; n < 40_000; n++) {
            String alphabet = n % 3 == 0 ? "ab" : n % 3 == 1 ? "abcde" : "abcdefghijklmnopqrstuvwxyz";
            String a = word(rnd, alphabet, n % 50 == 0 ? 60 : 10);
            String b = rnd.nextInt(4) == 0 ? a : word(rnd, alphabet, n % 50 == 0 ? 60 : 10);
            int want = levenshtein(a, b);
            for (int limit : limits) {
                int got = ed.within(a, b, limit);
                int expect = want <= limit ? want : limit + 1;
                assertEquals(expect, got, "within(\"" + a + "\", \"" + b + "\", " + limit + ")");
            }
            assertEquals(ed.within(a, b, 3), ed.within(b, a, 3), a + " / " + b);
        }
    }

    @Test
    void bkTreeAgreesWithBruteForceOnASmallAlphabet() {
        // a small alphabet makes many words collide at distance 0-2, which exercises the window edges
        Random rnd = new Random(2);
        EditDistance ed = new EditDistance();
        BkTree<String> tree = new BkTree<>(ed::within);
        Set<String> words = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            String w = word(rnd, "abcd", 8);
            tree.add(w);
            tree.add(w); // duplicates are ignored
            words.add(w);
        }
        assertEquals(words.size(), tree.size());
        EditDistance ref = new EditDistance();
        for (int q = 0; q < 400; q++) {
            String query = word(rnd, "abcde", 9);
            for (int r = 0; r <= 3; r++) {
                Map<String, Integer> want = new TreeMap<>();
                for (String w : words) {
                    int d = levenshtein(query, w);
                    if (d <= r) {
                        want.put(w, d);
                    }
                }
                Map<String, Integer> got = new TreeMap<>();
                for (Scored<String> s : tree.within(query, r)) {
                    Integer before = got.put(s.item(), (int) s.score());
                    assertEquals(null, before, s.item() + " reported twice");
                }
                assertEquals(want, got, "within(\"" + query + "\", " + r + ")");
            }
        }
        assertTrue(ref.within("", "", 0) == 0);
    }

    @Test
    void unionFindAgreesWithNaiveSetMerging() {
        Random rnd = new Random(3);
        for (int round = 0; round < 50; round++) {
            int n = 1 + rnd.nextInt(300);
            UnionFind uf = new UnionFind(n);
            int[] label = new int[n];
            for (int i = 0; i < n; i++) {
                label[i] = i;
            }
            for (int op = 0; op < n * 2; op++) {
                int a = rnd.nextInt(n);
                int b = rnd.nextInt(n);
                uf.union(a, b);
                int from = label[b];
                int to = label[a];
                for (int i = 0; i < n; i++) {
                    if (label[i] == from) {
                        label[i] = to;
                    }
                }
                int x = rnd.nextInt(n);
                int y = rnd.nextInt(n);
                assertEquals(label[x] == label[y], uf.connected(x, y), "round " + round + " op " + op);
            }
            for (int i = 0; i < n; i++) {
                int root = uf.find(i);
                assertEquals(root, uf.find(root), "a root is its own root");
                for (int j = 0; j < n; j += 7) {
                    assertEquals(label[i] == label[j], uf.find(i) == uf.find(j));
                }
            }
        }
    }

    @Test
    void longUnionChainsDoNotOverflowTheRank() {
        // rank is a byte; union by rank keeps it under log2(n), so 1M elements stay far below 127
        int n = 1 << 20;
        UnionFind uf = new UnionFind(n);
        for (int step = 1; step < n; step *= 2) {
            for (int i = 0; i + step < n; i += 2 * step) {
                uf.union(i, i + step);
            }
        }
        assertTrue(uf.connected(0, n - 1));
    }

    @Test
    void strongSoundKeyOfARealNameIsStableWhenAppliedAgain() {
        // VariantLayer compares strong(typed) with strong(key); if strong were not idempotent on real names,
        // a name typed as another name's key could miss it. Checked over every Latin key in the directory.
        NameDirectory d = SiteData.directory();
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            String k = d.key(i);
            if (!Text.isLatinWord(k)) {
                continue;
            }
            String s = SoundKey.strong(k);
            if (!SoundKey.strong(s).equals(s)) {
                bad.add(k + " -> " + s + " -> " + SoundKey.strong(s));
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " keys: " + bad.subList(0, Math.min(20, bad.size())));
    }

    @Test
    void strongSoundKeyIsIdempotentOnRandomSpellings() {
        // letters that take part in the merge rules, plus separators that sit between them in real keys
        Random rnd = new Random(10);
        for (int n = 0; n < 200_000; n++) {
            String w = word(rnd, "aeiouhckqxtpdbgsywn- '", 9);
            String s = SoundKey.strong(w);
            assertEquals(s, SoundKey.strong(s), "strong(\"" + w + "\")");
        }
        // regressions: separators split pairs ("baek-ho" kept its kh), and a merged k/t met a second h
        assertEquals(SoundKey.strong("baekho"), SoundKey.strong("baek-ho"));
        assertEquals(SoundKey.strong("bouk"), SoundKey.strong("bo-uk"));
        assertEquals(SoundKey.strong(SoundKey.strong("bickham")), SoundKey.strong("bickham"));
        assertEquals("a", SoundKey.strong("ahh"));
        assertEquals("sanjit", SoundKey.strong("sanjith"));
        assertEquals("sara", SoundKey.strong("sarah"));
    }

    @Test
    void soundKeysIgnoreCaseAccentsAndPunctuation() {
        Random rnd = new Random(4);
        for (int n = 0; n < 5000; n++) {
            String w = word(rnd, "abcdefghijklmnopqrstuvwxyz", 12);
            String s = SoundKey.strong(Text.fold(w));
            assertEquals(s, SoundKey.strong(Text.fold(w.toUpperCase())), w);
            assertEquals(s, SoundKey.strong(Text.fold(" " + w + " ")), w);
            for (int i = 0; i < s.length(); i++) {
                assertTrue(Character.isLetter(s.charAt(i)), w + " -> " + s);
                assertTrue(i == 0 || s.charAt(i) != s.charAt(i - 1), w + " -> " + s + " keeps a doubled letter");
            }
            assertEquals(SoundKey.skeletonOfStrong(s), SoundKey.skeleton(Text.fold(w)));
            assertTrue(SoundKey.skeleton(w).length() <= s.length());
        }
    }

    private static final String NASTY = "ab \t\n\r\u001E\u001F😀𐀀\uDBFF é<>\"'&,:|";

    /** Random text over letters, separators, whitespace, a valid surrogate pair and lone surrogate halves. */
    private static String nasty(Random rnd, int maxLen) {
        return word(rnd, NASTY, maxLen);
    }

    @Test
    void cleanedFieldsAlwaysSplitBackIntoTheSameColumns() {
        Random rnd = new Random(5);
        for (int n = 0; n < 20_000; n++) {
            String[] fields = new String[1 + rnd.nextInt(8)];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = Tsv.clean(nasty(rnd, 12));
            }
            String line = String.join("\t", fields);
            List<String> lines = Tsv.lines(line + "\n");
            assertTrue(lines.size() <= 1, "a cleaned row became " + lines.size() + " lines");
            String[] back = Tsv.split(line, '\t');
            assertEquals(fields.length, back.length, line);
            for (int i = 0; i < fields.length; i++) {
                assertEquals(fields[i], back[i]);
                assertEquals(fields[i], Tsv.clean(fields[i]), "clean is idempotent");
                assertFalse(hasLoneSurrogate(fields[i]), "lone surrogate survived clean: " + fields[i]);
                assertEquals(-1, fields[i].indexOf('\u001E'));
                assertEquals(-1, fields[i].indexOf('\u001F'));
            }
        }
        assertEquals("", Tsv.clean(null));
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void entriesRoundTripThroughTheirTsvLine() {
        Random rnd = new Random(6);
        for (int n = 0; n < 20_000; n++) {
            List<OriginStep> chain = new ArrayList<>();
            int steps = rnd.nextInt(4);
            for (int i = 0; i < steps; i++) {
                chain.add(new OriginStep(nasty(rnd, 10), nasty(rnd, 10)));
            }
            NameEntry e = new NameEntry(nasty(rnd, 10), nasty(rnd, 10), nasty(rnd, 6), nasty(rnd, 20),
                    nasty(rnd, 40), chain);
            String key = Tsv.clean(nasty(rnd, 8));
            String line = e.toTsv(key);
            List<String> lines = Tsv.lines(line);
            assertTrue(lines.size() <= 1 && (lines.isEmpty() || lines.get(0).equals(line)), "row broke into lines");
            String[] f = Tsv.split(line, '\t');
            assertEquals(7, f.length, line);
            assertEquals(key, f[0]);
            NameEntry back = NameEntry.fromTsv(f);
            assertEquals(Tsv.clean(e.display()), back.display());
            assertEquals(Tsv.clean(e.language()), back.language());
            assertEquals(Tsv.clean(e.gender()), back.gender());
            assertEquals(Tsv.clean(e.meaning()), back.meaning());
            assertEquals(Tsv.clean(e.summary()), back.summary());
            assertEquals(chain.size(), back.chain().size(), "chain length changed: " + line);
            for (int i = 0; i < chain.size(); i++) {
                assertEquals(Tsv.clean(chain.get(i).language()), back.chain().get(i).language());
                assertEquals(Tsv.clean(chain.get(i).form()), back.chain().get(i).form());
            }
            assertEquals(line, back.toTsv(key), "a second round trip changes nothing");
        }
    }

    @Test
    void topKMatchesAFullSort() {
        Random rnd = new Random(7);
        for (int n = 0; n < 3000; n++) {
            int size = rnd.nextInt(60);
            float[] scores = new float[size];
            String[] keys = new String[size];
            for (int i = 0; i < size; i++) {
                // coarse values so ties are common; some zero and negative scores, which must be dropped
                scores[i] = (rnd.nextInt(12) - 3) / 4f;
                keys[i] = "k" + i;
            }
            int k = rnd.nextInt(15) - 1;
            List<Scored<String>> got = MeaningSearch.topK(scores, keys, k);
            List<Float> positive = new ArrayList<>();
            for (float s : scores) {
                if (s > 0) {
                    positive.add(s);
                }
            }
            positive.sort((a, b) -> Float.compare(b, a));
            List<Float> want = positive.subList(0, Math.max(0, Math.min(k, positive.size())));
            List<Float> gotScores = new ArrayList<>();
            Map<String, Float> byKey = new HashMap<>();
            for (int i = 0; i < size; i++) {
                byKey.put(keys[i], scores[i]);
            }
            for (Scored<String> s : got) {
                gotScores.add((float) s.score());
                assertEquals(byKey.get(s.item()), (float) s.score(), "score belongs to its key");
            }
            assertEquals(want, gotScores, "k=" + k);
            assertEquals(got.size(), got.stream().map(Scored::item).distinct().count());
        }
    }

    @Test
    void decimalParsesWhatTheBuildWrites() {
        Random rnd = new Random(8);
        for (int n = 0; n < 20_000; n++) {
            double v = (rnd.nextDouble() - 0.5) * Math.pow(10, rnd.nextInt(6));
            String s = String.format(java.util.Locale.ROOT, "%." + rnd.nextInt(5) + "f", v);
            assertEquals(Float.parseFloat(s), Tsv.decimal(s), 1e-4 * Math.max(1, Math.abs(v)), s);
        }
    }

    @Test
    void foldIsIdempotentAndLowercase() {
        Random rnd = new Random(9);
        String alphabet = "aAzZéÉøØßæŒþÐñÑçÇ -'İıΣσさ";
        for (int n = 0; n < 20_000; n++) {
            String w = word(rnd, alphabet, 10);
            String f = Text.fold(w);
            assertEquals(f, Text.fold(f), "fold(fold(" + w + "))");
        }
    }
}
