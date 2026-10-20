package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The four layers, on a hand-built directory in the same format IndexBuilder writes. */
class MatcherTest {

    /** state: 2 = has an origin chain, 1 = has entries but no chain, 0 = a recorded spelling with no entry. */
    private static String row(String key, String display, int group, int state) {
        return String.join("\t", key, display, String.valueOf(group), String.valueOf(state)) + "\n";
    }

    private static String row(String key, String display, int group, boolean origin) {
        return row(key, display, group, origin ? 2 : 0);
    }

    private static final NameDirectory DIR = NameDirectory.parse(
            row("abgaro", "Abgaro", 7, false)        // a spelling with no entry and no variant that has one
            + row("abhijit", "Abhijit", 0, true)
            + row("michael", "Michael", 1, true)
            + row("mohammed", "Mohammed", 2, false)  // a recorded variant spelling without its own entry
            + row("muhammad", "Muhammad", 2, true)
            + row("mykal", "Mykal", 1, false)
            + row("ranjit", "Ranjit", 5, true)
            + row("sanjay", "Sanjay", 3, true)
            + row("sanjeet", "Sanjeet", 4, true)
            + row("wei", "Wei", 6, 1));            // has an entry (e.g. Wikidata) but no origin chain

    // Tiny model: two origins, "jit$" strongly Sanskrit.
    private static final OriginModel MODEL = OriginModel.parse(
            "C\tSanskrit\t-0.7\t-9\n"
            + "C\tHebrew\t-0.7\t-9\n"
            + "G\tjit\t0:4.0\n"
            + "G\tit$\t0:4.0\n"
            + "G\tjit$\t0:4.0\n"
            + "G\t^ab\t0:2.0,1:1.0\n"
            + "E\tjit\t0\tAbhijit, Ranjit\n"
            + "E\tjit$\t0\tAbhijit, Ranjit\n"
            + "E\turj\t0\tSurjeet\n"
            + "T\t1.0\n"
            + "F\t0.6\n");

    private final Matcher matcher = Matcher.standard(DIR, () -> MODEL);

    @Test
    void exactHitWithOrigin() {
        MatchResult r = matcher.match("MICHAEL");
        assertEquals(new MatchResult.Exact("MICHAEL", "michael"), r);
    }

    @Test
    void unknownSpellingFindsTheSoundAlike() {
        MatchResult r = matcher.match("Sanjith");
        MatchResult.Variant v = assertInstanceOf(MatchResult.Variant.class, r);
        assertEquals("sanjeet", v.key());
    }

    @Test
    void recordedVariantWithoutEntryFollowsItsGroup() {
        assertEquals("muhammad", assertInstanceOf(MatchResult.Variant.class, matcher.match("Mohammed")).key());
        assertEquals("michael", assertInstanceOf(MatchResult.Variant.class, matcher.match("Mykal")).key());
    }

    @Test
    void knownNameWithoutVariantsIsNotRedirectedToASoundAlike() {
        assertEquals(new MatchResult.Exact("Wei", "wei"), matcher.match("Wei"));
    }

    @Test
    void recordedSpellingWithNothingToShowIsNotAnEmptyExactHit() {
        // regression (Codex review): "Abgaro" used to come back Exact with no entries, rendering a blank page
        MatchResult r = matcher.match("Abgaro");
        assertTrue(!(r instanceof MatchResult.Exact) && !(r instanceof MatchResult.Variant), r.toString());
    }

    @Test
    void noVariantFallsBackToTheGuess() {
        MatchResult.Inferred i = assertInstanceOf(MatchResult.Inferred.class, matcher.match("Surjit"));
        assertEquals("Sanskrit", i.origins().get(0).item());
        assertTrue(i.origins().get(0).score() >= 0.6);
        // the end-anchored piece comes first; the middle "jit" has the same examples so it is not repeated
        assertEquals(java.util.List.of("-jit: Abhijit, Ranjit", "-urj-: Surjeet"), i.evidence());
    }

    @Test
    void nothingConfidentGivesNearestNames() {
        MatchResult.None n = assertInstanceOf(MatchResult.None.class, matcher.match("Sanjaq"));
        assertEquals("Sanjay", n.suggestions().get(0));
    }

    @Test
    void prefixSearchUsesSortedKeys() {
        assertEquals(2, DIR.withPrefix("san", 10).size());
        assertEquals(0, DIR.withPrefix("zz", 10).size());
    }
}
