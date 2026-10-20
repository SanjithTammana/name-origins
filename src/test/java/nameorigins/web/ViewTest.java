package nameorigins.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import nameorigins.core.MatchResult;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginStep;
import nameorigins.core.Scored;
import org.junit.jupiter.api.Test;

class ViewTest {

    private static final NameEntry SOPHIA = new NameEntry("Sophia", "English", "female", "wisdom",
            "Chiefly from Latin Sophia, from Ancient Greek Σοφία.",
            List.of(new OriginStep("Ancient Greek", "Σοφία (Sophía)"), new OriginStep("Latin", "Sophia")));

    @Test
    void lineageRunsOldestFirstAndEndsWithTheName() {
        String html = View.result(new MatchResult.Exact("Sophia", "sophia"), List.of(SOPHIA), List.of());
        int greek = html.indexOf("Ancient Greek");
        int latin = html.indexOf(">Latin<");
        int now = html.indexOf("step-now");
        assertTrue(greek > 0 && latin > greek && now > latin, html);
        assertTrue(html.contains("Σοφία (Sophía)"));
    }

    @Test
    void everythingTypedOrScrapedIsEscaped() {
        String html = View.result(new MatchResult.None("<img src=x onerror=alert(1)>", List.of("a\"b")),
                List.of(), List.of());
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;img"));
        assertTrue(html.contains("data-name=\"a&quot;b\""));
    }

    @Test
    void variantSaysItIsNotTheTypedSpelling() {
        String html = View.result(new MatchResult.Variant("Sanjith", "sanjeet", List.of()),
                List.of(new NameEntry("Sanjeet", "English", "male", "full victory", "", List.of())), List.of());
        assertTrue(html.contains("isn’t recorded as typed"));
        assertTrue(html.contains("<strong>Sanjeet</strong>"));
    }

    @Test
    void guessIsLabelledAsAGuess() {
        String html = View.result(new MatchResult.Inferred("Surjit", List.of(new Scored<>("Sanskrit", 0.71)),
                List.of("-jit: Abhijit"), List.of()), List.of(), List.of());
        assertTrue(html.contains("a guess from its letter patterns, not a record"));
        assertTrue(html.contains("71%"));
    }

    @Test
    void wikidataOnlyEntryLinksToWikidataAndHasNoStraySeparator() {
        NameEntry wei = new NameEntry("Wei", "", "male", "",
                "Listed on Wikidata as a given name. No etymology is recorded for it yet.", List.of());
        String html = View.result(new MatchResult.Exact("Wei", "wei"), List.of(wei), List.of());
        assertTrue(html.contains("<p class=\"entry-meta\">male name</p>"), html);
        assertTrue(html.contains("wikidata.org/w/index.php?search=Wei"));
        assertFalse(html.contains("Wiktionary entry"));
        assertTrue(View.encodeQuery("Zoë Ann").equals("Zo%C3%AB%20Ann"));
    }

    @Test
    void aRecordedNameThatFollowsItsGroupIsNotCalledUnrecorded() {
        // "Mykal" has a Wikidata entry; it follows its group to Michael for the origin, and used to be told
        // "isn't recorded as typed"
        String html = View.result(new MatchResult.Variant("Mykal", "michael", List.of(), true),
                List.of(new NameEntry("Michael", "English", "male", "", "", List.of())), List.of());
        assertFalse(html.contains("isn’t recorded"), html);
        assertTrue(html.contains("is recorded, but with no origin"), html);
        assertTrue(html.contains("<strong>Michael</strong>"), html);
    }

    @Test
    void aMatchWhoseDetailsDidNotDownloadSaysSoInsteadOfRenderingNothing() {
        // Data.entries answers an empty list when the shard request fails; Exact used to render an empty string
        String exact = View.result(new MatchResult.Exact("Michael", "michael"), List.of(), List.of());
        assertTrue(exact.contains("didn’t load"), exact);
        String variant = View.result(new MatchResult.Variant("Sanjith", "sanjeet", List.of()), List.of(), List.of());
        assertTrue(variant.contains("didn’t load"), variant);
        assertTrue(View.result(new MatchResult.Exact("<b>", "b"), List.of(), List.of()).contains("&lt;b&gt;"));
    }

    @Test
    void negligibleOddsAreHiddenAndCertaintyIsNotOverstated() {
        String html = View.result(new MatchResult.Inferred("Takeshiro",
                List.of(new Scored<>("Japanese", 0.998), new Scored<>("Persian", 0.001)), List.of(), List.of()),
                List.of(), List.of());
        assertTrue(html.contains("over 99%"));
        assertFalse(html.contains("Persian"));
    }
}
