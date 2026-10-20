package nameorigins.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One sourced entry for a name in one language. A name can have several (Michael in English, German, ...).
 *
 * @param chain oldest step first, e.g. Biblical Hebrew -> Koine Greek -> Latin -> Old French -> Middle English
 */
public record NameEntry(
        String display, String language, String gender, String meaning, String summary, List<OriginStep> chain,
        String wikipediaTitle) {

    /** Wiktionary and Wikidata entries have no Wikipedia source title. */
    public NameEntry(String display, String language, String gender, String meaning, String summary,
            List<OriginStep> chain) {
        this(display, language, gender, meaning, summary, chain, "");
    }

    private static final char STEP = '\u001E';
    private static final char PART = '\u001F';

    /** Oldest known language, or "" when the entry has no origin. */
    public String oldestLanguage() {
        return chain.isEmpty() ? "" : chain.get(0).language();
    }

    /** Serializes as one TSV line (key first), the format of the entries-*.tsv files. */
    public String toTsv(String key) {
        StringBuilder c = new StringBuilder();
        for (OriginStep s : chain) {
            if (c.length() > 0) {
                c.append(STEP);
            }
            c.append(Tsv.clean(s.language())).append(PART).append(Tsv.clean(s.form()));
        }
        return String.join("\t", Tsv.clean(key), Tsv.clean(display), Tsv.clean(language), Tsv.clean(gender),
                Tsv.clean(meaning), Tsv.clean(summary), c.toString(), Tsv.clean(wikipediaTitle));
    }

    /** Parses a line written by {@link #toTsv}; fields[0] is the key. */
    public static NameEntry fromTsv(String[] f) {
        List<OriginStep> chain = new ArrayList<>();
        if (f.length > 6 && !f[6].isEmpty()) {
            for (String step : Tsv.split(f[6], STEP)) {
                String[] p = Tsv.split(step, PART);
                chain.add(new OriginStep(p[0], p.length > 1 ? p[1] : ""));
            }
        }
        return new NameEntry(f[1], f[2], f[3], f[4], f[5], chain, f.length > 7 ? f[7] : "");
    }
}
