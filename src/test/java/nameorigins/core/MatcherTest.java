package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The four layers, on a hand-built directory in the same format IndexBuilder writes. */
class MatcherTest {

    private static String row(String key, String display, int group, boolean origin) {
        return String.join("\t", key, display, String.valueOf(group), origin ? "1" : "0") + "\n";
    }

    private static final NameDirectory DIR = NameDirectory.parse(
            row("abhijit", "Abhijit", 0, true)
            + row("michael", "Michael", 1, true)
            + row("mohammed", "Mohammed", 2, false)  // a recorded variant spelling without its own entry
            + row("muhammad", "Muhammad", 2, true)
            + row("mykal", "Mykal", 1, false)
            + row("ranjit", "Ranjit", 5, true)
            + row("sanjay", "Sanjay", 3, true)
            + row("sanjeet", "Sanjeet", 4, true)
            + row("wei", "Wei", 6, false));        // known from Wikidata only, no origin chain

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
