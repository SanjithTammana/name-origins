package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class StructuresTest {

    private static BkTree<String> treeOf(List<String> words) {
        EditDistance ed = new EditDistance();
        BkTree<String> t = new BkTree<>(ed::within);
        words.forEach(t::add);
        return t;
    }

    @Test
    void bkTreeFindsExactlyTheWordsWithinRadius() {
        BkTree<String> t = treeOf(List.of("sanjay", "sanjit", "sanjeet", "sanjiv", "michael", "miguel", "anna"));
        Set<String> got = new TreeSet<>();
        t.within("sanjith", 2).forEach(s -> got.add(s.item()));
        assertEquals(Set.of("sanjit", "sanjiv"), got);
    }

    @Test
    void bkTreeAgreesWithBruteForceAndSkipsMostNodes() {
        Random rnd = new Random(7);
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 4 + rnd.nextInt(6);
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + rnd.nextInt(26)));
            }
            words.add(sb.toString());
        }
        BkTree<String> t = treeOf(words);
        EditDistance ed = new EditDistance();
        for (int q = 0; q < 20; q++) {
            String query = words.get(rnd.nextInt(words.size()));
            Set<String> expected = new TreeSet<>();
            for (String w : words) {
                if (ed.within(query, w, 2) <= 2) {
                    expected.add(w);
                }
            }
            Set<String> got = new TreeSet<>();
            t.within(query, 2).forEach(s -> got.add(s.item()));
            assertEquals(expected, got);
            assertTrue(t.lastVisited() < words.size(), "visited " + t.lastVisited());
        }
    }

    @Test
    void topKOfZeroIsEmptyNotACrash() {
        assertEquals(List.of(), MeaningSearch.topK(new float[] {1f}, new String[] {"anna"}, 0));
        assertEquals("b", MeaningSearch.topK(new float[] {0.2f, 0.9f, 0.5f}, new String[] {"a", "b", "c"}, 1).get(0).item());
    }

    @Test
    void unionFindGroupsTransitiveLinks() {
        UnionFind uf = new UnionFind(5);
        uf.union(0, 1);
        uf.union(1, 2);
        assertTrue(uf.connected(0, 2));
        assertFalse(uf.connected(0, 3));
        uf.union(3, 4);
        uf.union(4, 2);
        assertTrue(uf.connected(0, 3));
    }
}
