package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The matcher on the real shipped data, the way the page builds it (Data.loadCore). */
class GoldenMatcherTest {

    private static MatchResult match(String q) {
        return SiteData.matcher().match(q);
    }

    private static NameDirectory dir() {
        return SiteData.directory();
    }

    @Test
    void michaelIsExactAndStartsInBiblicalHebrew() {
        MatchResult.Exact e = assertInstanceOf(MatchResult.Exact.class, match("Michael"));
        assertEquals("michael", e.key());
        List<NameEntry> entries = SiteData.entries("michael");
        assertFalse(entries.isEmpty());
        assertEquals("Biblical Hebrew", entries.get(0).chain().get(0).language(), entries.get(0).toString());
    }

    @Test
    void sanjithIsASpellingOfSanjeet() {
        MatchResult.Variant v = assertInstanceOf(MatchResult.Variant.class, match("Sanjith"));
        assertEquals("sanjeet", v.key());
        assertFalse(v.recorded(), "Sanjith is not in the data");
    }

    @Test
    void mykalLandsInMichaelsGroup() {
        MatchResult.Variant v = assertInstanceOf(MatchResult.Variant.class, match("Mykal"));
        assertTrue(v.recorded(), "Mykal has an entry of its own");
        int michael = dir().slot("michael");
        int got = dir().slot(v.key());
        assertTrue(got >= 0 && dir().hasOrigin(got), v.toString());
        boolean sameGroup = got == michael;
        for (int s : dir().variantsOf(michael)) {
            sameGroup |= s == got;
        }
        assertTrue(sameGroup, v.key() + " is not in Michael's variant group");
    }

    @Test
    void weiIsAnExactWikidataNameInChinese() {
        assertEquals(new MatchResult.Exact("Wei", "wei"), match("Wei"));
        List<NameEntry> entries = SiteData.entries("wei");
        assertFalse(entries.isEmpty());
        assertTrue(entries.get(0).language().contains("Chinese"), entries.toString());
    }

    @Test
    void abgaroIsNotAnEmptyResult() {
        MatchResult r = match("Abgaro");
        String key = r instanceof MatchResult.Exact e ? e.key() : r instanceof MatchResult.Variant v ? v.key() : null;
        if (key != null) {
            assertFalse(SiteData.entries(key).isEmpty(), r + " has nothing to show");
        }
    }

    @Test
    void gibberishFindsNothing() {
        assertInstanceOf(MatchResult.None.class, match("Qwzx"));
    }

    @Test
    void accentsAndCaseDoNotChangeTheAnswer() {
        for (String[] pair : new String[][] {{"José", "JOSE"}, {"Zoë", "zoe"}, {"  Michael ", "MICHAEL"},
                {"Søren", "soren"}, {"Ångström", "angstrom"}}) {
            assertEquals(strip(match(pair[0])), strip(match(pair[1])), pair[0] + " vs " + pair[1]);
        }
    }

    /** The result without the query echo, which differs by design. */
    private static String strip(MatchResult r) {
        return switch (r) {
            case MatchResult.Exact e -> "Exact " + e.key();
            case MatchResult.Variant v -> "Variant " + v.key() + " " + v.alternatives();
            case MatchResult.Inferred i -> "Inferred " + i.origins() + " " + i.evidence() + " " + i.suggestions();
            case MatchResult.None n -> "None " + n.suggestions();
        };
    }

    @Test
    void oddInputNeverThrows() {
        for (String q : List.of("さくら", "Σοφία", "محمد", "李", "😀", "\uD83D", "", " ", "-", "'", "a", "ab",
                "x".repeat(300), "Anne-Marie", "O'Brien", "Jean Paul", "<script>", "123", "\t\n")) {
            MatchResult r = match(q);
            assertEquals(q, r.query());
        }
    }

    /**
     * The page renders an Exact or Variant result from the key's entry rows; a key without rows is a blank page
     * (the Abgaro bug). Checked over every recorded spelling without an entry, plus a random sample of names and
     * of one-edit misspellings of them.
     */
    @Test
    void everyExactOrVariantAnswerHasEntriesToShow() {
        NameDirectory d = dir();
        List<String> queries = new ArrayList<>();
        Random rnd = new Random(20261020);
        for (int i = 0; i < d.size(); i++) {
            if (!d.hasEntries(i)) {
                queries.add(d.display(i));
            }
        }
        for (int n = 0; n < 4000; n++) {
            String k = d.display(rnd.nextInt(d.size()));
            queries.add(k);
            queries.add(mutate(k, rnd));
        }
        List<String> bad = new ArrayList<>();
        for (String q : queries) {
            MatchResult r = match(q);
            String key = null;
            if (r instanceof MatchResult.Exact e) {
                key = e.key();
                if (!key.equals(Text.fold(q))) {
                    bad.add(q + " -> Exact for a different key " + key);
                }
            } else if (r instanceof MatchResult.Variant v) {
                key = v.key();
                if (v.alternatives().contains(d.display(d.slot(key)))) {
                    bad.add(q + " -> " + key + " lists itself as an alternative");
                }
            }
            if (key != null && SiteData.entries(key).isEmpty()) {
                bad.add(q + " -> " + r);
            }
            if (bad.size() >= 20) {
                break;
            }
        }
        if (!bad.isEmpty()) {
            fail(bad.size() + " answers with nothing to show:\n  " + String.join("\n  ", bad));
        }
    }

    private static String mutate(String s, Random rnd) {
        StringBuilder b = new StringBuilder(s);
        int at = rnd.nextInt(b.length() + 1);
        char c = (char) ('a' + rnd.nextInt(26));
        switch (rnd.nextInt(3)) {
            case 0 -> b.insert(at, c);
            case 1 -> {
                if (at < b.length()) {
                    b.deleteCharAt(at);
                }
            }
            default -> {
                if (at < b.length()) {
                    b.setCharAt(at, c);
                }
            }
        }
        return b.toString();
    }

    @Test
    void suggestionsAreRecordedNamesWithAnOrigin() {
        Random rnd = new Random(5);
        NameDirectory d = dir();
        for (int n = 0; n < 300; n++) {
            String q = mutate(mutate(d.display(rnd.nextInt(d.size())), rnd), rnd) + "q";
            MatchResult r = match(q);
            List<String> suggestions = r instanceof MatchResult.None no ? no.suggestions()
                    : r instanceof MatchResult.Inferred i ? i.suggestions() : List.of();
            assertTrue(suggestions.size() <= 5, q + ": " + suggestions);
            assertEquals(suggestions.size(), suggestions.stream().distinct().count(), q + ": " + suggestions);
            for (String s : suggestions) {
                int slot = d.slot(Text.fold(s));
                assertTrue(slot >= 0 && d.hasOrigin(slot), q + " suggests " + s);
            }
        }
    }

    @Test
    void guessesAreLabelledWithKnownOriginsAndSensibleOdds() {
        OriginModel m = SiteData.model();
        for (String q : List.of("Surjit", "Abhijeetan", "Takeshiro", "Wolfhardt", "Oluwaseunfunmi")) {
            if (match(q) instanceof MatchResult.Inferred i) {
                assertFalse(i.origins().isEmpty());
                assertTrue(i.origins().get(0).score() >= m.confidenceFloor(), i.toString());
                for (Scored<String> o : i.origins()) {
                    assertTrue(m.origins().contains(o.item()), o.item());
                    assertTrue(o.score() >= 0 && o.score() <= 1, i.toString());
                }
            }
        }
    }

    @Test
    void meaningSearchOnTheBlendFindsVictoryNames() {
        BlendSearch blend = new BlendSearch(TfIdfIndex.parse(SiteData.text("meaning-tfidf.tsv")),
                EmbeddingIndex.parse(SiteData.text("meaning-emb.tsv")));
        List<Scored<String>> top = blend.search("victory", 50);
        assertEquals(50, top.size());
        List<String> keys = top.stream().map(Scored::item).toList();
        assertTrue(keys.contains("victoria"), keys.toString());
        for (Scored<String> s : top) {
            assertTrue(dir().slot(s.item()) >= 0, s.item());
        }
        assertTrue(blend.search("qqqzzzxx", 10).isEmpty());
        assertTrue(blend.search("", 10).isEmpty());
    }
}
