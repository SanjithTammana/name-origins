package nameorigins.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nameorigins.core.MatchResult;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginStep;
import nameorigins.core.Scored;
import org.junit.jupiter.api.Test;

/**
 * Every string View puts into HTML comes from Wiktionary, Wikidata or the visitor. The check: render a random
 * result twice, once with hostile text in every field and once with the same text where each markup character
 * is replaced by a letter. Escaping keeps the page's structure, so both renderings must have exactly the same
 * number of raw markup characters, and every raw '<' must open one of View's own tags.
 */
class ViewEscapingTest {

    private static final String ALPHABET = "ab <>\"'&=/;:é ";
    private static final Pattern TAG = Pattern.compile(
            "</?(p|strong|ul|li|button|article|header|h2|h3|ol|span|details|summary|section|a)[ >]");

    private static String hostile(Random rnd) {
        int len = rnd.nextInt(14);
        StringBuilder sb = new StringBuilder();
        if (rnd.nextInt(8) == 0) {
            sb.append("Listed on Wikidata"); // takes the Wikidata link branch for summaries
        }
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        if (rnd.nextInt(10) == 0) {
            sb.append("<script>alert(1)</script><img src=x onerror=alert(1)>\"><a href='javascript:x'>");
        }
        return sb.toString();
    }

    /** Same length and emptiness, no markup characters. */
    private static String defanged(String s) {
        return s.replaceAll("[<>\"'&]", "x");
    }

    private static NameEntry entry(Random rnd, UnaryOperator<String> f, long seed) {
        Random r = new Random(seed);
        List<OriginStep> chain = new ArrayList<>();
        int steps = r.nextInt(4);
        for (int i = 0; i < steps; i++) {
            chain.add(new OriginStep(f.apply(hostile(r)), f.apply(hostile(r))));
        }
        return new NameEntry(f.apply(hostile(r)), f.apply(hostile(r)), f.apply(hostile(r)), f.apply(hostile(r)),
                f.apply(hostile(r)), chain);
    }

    private static List<String> names(Random r, UnaryOperator<String> f) {
        List<String> out = new ArrayList<>();
        int n = r.nextInt(4);
        for (int i = 0; i < n; i++) {
            out.add(f.apply(hostile(r)));
        }
        return out;
    }

    /** Renders one random result; the same seed with a different f gives the same structure. */
    private static String render(long seed, UnaryOperator<String> f) {
        Random r = new Random(seed);
        String query = f.apply(hostile(r));
        String key = f.apply(hostile(r));
        List<NameEntry> entries = new ArrayList<>();
        int count = r.nextInt(4);
        for (int i = 0; i < count; i++) {
            entries.add(entry(r, f, r.nextLong()));
        }
        List<String> similar = names(r, f);
        MatchResult result = switch (r.nextInt(5)) {
            case 0 -> new MatchResult.Exact(query, key);
            case 1 -> new MatchResult.Variant(query, key, names(r, f));
            case 2 -> {
                List<Scored<String>> origins = new ArrayList<>();
                int n = r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    origins.add(new Scored<>(f.apply(hostile(r)), r.nextDouble()));
                }
                yield new MatchResult.Inferred(query, origins, names(r, f), names(r, f));
            }
            case 3 -> new MatchResult.None(query, names(r, f));
            default -> null;
        };
        if (result == null) {
            return View.meaningResults(query, names(r, f));
        }
        boolean noEntries = result instanceof MatchResult.Inferred || result instanceof MatchResult.None;
        return View.result(result, noEntries ? List.of() : entries, similar);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    @Test
    void hostileTextNeverAddsMarkup() {
        for (long seed = 0; seed < 20_000; seed++) {
            String hostile = render(seed, UnaryOperator.identity());
            String safe = render(seed, ViewEscapingTest::defanged);
            for (char c : new char[] {'<', '>', '"', '\''}) {
                assertEquals(count(safe, c), count(hostile, c),
                        "seed " + seed + ": count of " + c + " differs\n" + hostile + "\n" + safe);
            }
            for (int i = hostile.indexOf('<'); i >= 0; i = hostile.indexOf('<', i + 1)) {
                Matcher m = TAG.matcher(hostile);
                assertTrue(m.find(i) && m.start() == i,
                        "seed " + seed + ": raw '<' that is not one of View's tags at " + i + ":\n" + hostile);
            }
        }
    }

    @Test
    void wikidataLinkKeepsTheWholeNameInTheQuery() {
        // encodeQuery worked one UTF-16 char at a time, so a character outside the BMP (two chars) was encoded
        // as two "?" bytes and the link searched for "??"
        assertEquals("%F0%A0%80%80", View.encodeQuery("𠀀"));
        assertEquals("Zo%C3%AB%20%F0%9F%98%80", View.encodeQuery("Zoë 😀"));
        assertEquals("a-b.c_d", View.encodeQuery("a-b.c_d"));
        assertEquals("%3C%22%26%27%3E", View.encodeQuery("<\"&'>"));
    }
}
