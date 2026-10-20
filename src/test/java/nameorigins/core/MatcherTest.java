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
            + row("savoo", "Savoo", 8, true)
            + row("sayah", "Sayah", 9, true)
            + row("wei", "Wei", 6, 1)              // has an entry (e.g. Wikidata) but no origin chain
            + row("yousef", "Yousef", 10, true));

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
    void vowelRespellingsFindTheirNameByConsonantSkeleton() {
        assertEquals("muhammad", assertInstanceOf(MatchResult.Variant.class, matcher.match("Mohamad")).key());
        assertEquals("yousef", assertInstanceOf(MatchResult.Variant.class, matcher.match("Yusuf")).key());
    }

    @Test
    void aShortSkeletonNeedsAOneEditRespelling() {
        // "sv" and "s" are all Siwoo and Seoah share with Savoo and Sayah; two edits apart is a different name
        for (String q : new String[] {"Siwoo", "Seoah"}) {
            MatchResult r = matcher.match(q);
            assertTrue(!(r instanceof MatchResult.Variant), q + " -> " + r);
        }
        assertEquals("savoo", assertInstanceOf(MatchResult.Variant.class, matcher.match("Savo")).key());
    }

    @Test
    void usageModelOnlyAnswersWhenOriginModelAbstains() {
        OriginModel disabledOrigin = OriginModel.parse("C\tHebrew\t0\t-1\nT\t1\nF\t1.01\n");
        OriginModel usage = OriginModel.parse("C\tused in Korean\t0\t-1\n"
                + "G\t^xy\t0:2\nG\t^xyz\t0:2\nG\txyz\t0:2\nG\tyzn\t0:2\n"
                + "G\tznor\t0:2\nG\tnor\t0:2\nG\torb\t0:2\n"
                + "E\t^xyz\t0\tXyzan\nT\t1\nF\t0.20\n");
        MatchResult.Inferred fallback = assertInstanceOf(MatchResult.Inferred.class,
                Matcher.standard(DIR, () -> disabledOrigin, () -> usage).match("Xyznorb"));
        assertEquals("used in Korean", fallback.origins().get(0).item());
        OriginModel confidentOrigin = OriginModel.parse("C\tHebrew\t0\t-1\nT\t1\nF\t0.20\n");
        MatchResult.Inferred first = assertInstanceOf(MatchResult.Inferred.class,
                Matcher.standard(DIR, () -> confidentOrigin, () -> usage).match("Xyznorb"));
        assertEquals("Hebrew", first.origins().get(0).item());
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
