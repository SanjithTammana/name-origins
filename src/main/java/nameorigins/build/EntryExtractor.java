package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginStep;
import nameorigins.core.Text;

/**
 * Turns one kaikki.org entry (parsed JSON) into what the app shows: the origin chain, the meaning, a short
 * summary, gender and spelling variants.
 *
 * Wiktionary encodes etymology as templates. For der/bor/inh (derived, borrowed, inherited) and their "+"
 * variants, args are: 1 = this entry's language, 2 = source language code, 3 = source word, 4 = alternative
 * display, 5 or t or gloss = meaning, lit = literal meaning. Templates are written newest-first
 * ("From Middle English ..., from Old French ..., from Latin ..."), so the chain is reversed for display.
 */
public final class EntryExtractor {

    /** What one entry contributes to the index. */
    public record Extracted(String key, NameEntry entry, List<String> variants, List<String> meanings) {}

    private static final Set<String> CHAIN_TEMPLATES = Set.of(
            "der", "der+", "bor", "bor+", "inh", "inh+", "lbor", "lbor+", "slbor", "ubor", "translit", "uder");
    private static final Pattern CODE_FORM = Pattern.compile("^([a-z]{2,3}(?:-[a-z]{2,4})*):(.+)$");
    private static final Pattern QUOTED = Pattern.compile("“([^”]{2,80})”");
    /** "Borrowed from ", "Learned borrowing from ", "Transliteration of ": what an expansion says before the language. */
    private static final Pattern EXPANSION_PREFIX = Pattern.compile("^(?:[A-Za-z -]*? (?:from|of) )?");
    /** "Germanic", "Old Iranian", "Italian, Portuguese, and Spanish"; not "[Term?]" or a word in another script. */
    private static final Pattern LANGUAGE_NAME = Pattern.compile("[A-Z][\\p{L}'–-]*(?:,? [\\p{L}'–-]+)*");
    private static final int MAX_STEPS = 8;
    private static final int MAX_SUMMARY = 260;

    private final Map<String, String> languageNames;
    private final List<String> namesLongestFirst;

    /**
     * @param languageNames language code -> name (from langs.tsv). Names voted "unknown" are ignored: the dump
     *     labels a few codes that way, Proto-Indo-European (ine-pro) among them.
     */
    public EntryExtractor(Map<String, String> languageNames) {
        this.languageNames = new HashMap<>(languageNames);
        this.languageNames.values().removeIf(name -> name.equals("unknown"));
        Set<String> unique = new LinkedHashSet<>(this.languageNames.values());
        this.namesLongestFirst = new ArrayList<>(unique);
        this.namesLongestFirst.sort((a, b) -> b.length() - a.length());
    }

    public Extracted extract(JsonNode e) {
        String display = e.path("word").asText();
        String key = Text.fold(display);
        String language = e.path("lang").asText();
        String ownCode = e.path("lang_code").asText();

        List<OriginStep> newestFirst = chainFromTemplates(e, ownCode);
        if (newestFirst.isEmpty()) {
            newestFirst = chainFromText(glossText(e) + " " + e.path("etymology_text").asText(""));
        }
        List<OriginStep> chain = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            chain.add(newestFirst.get(i));
        }

        List<String> meanings = meanings(e, display);
        NameEntry entry = new NameEntry(display, language, gender(e), String.join("; ", meanings),
                summary(e.path("etymology_text").asText("")), chain);
        return new Extracted(key, entry, variants(e), meanings);
    }

    private List<OriginStep> chainFromTemplates(JsonNode e, String ownCode) {
        List<OriginStep> steps = new ArrayList<>();
        List<OriginStep> fromTreeTemplates = new ArrayList<>();
        for (JsonNode t : e.path("etymology_templates")) {
            String name = t.path("name").asText();
            JsonNode args = t.path("args");
            if (CHAIN_TEMPLATES.contains(name)) {
                String code = args.path("2").asText();
                if (code.isEmpty() || code.equals(ownCode)) {
                    continue;
                }
                String expansion = t.path("expansion").asText("");
                String lang = languageNames.getOrDefault(code, nameFromExpansion(args, expansion));
                if (lang != null) {
                    addStep(steps, lang, form(args, expansion, lang));
                }
            } else if (name.equals("ety") || name.equals("etymon")) {
                // newer tree templates: args like {"2": ":bor", "3": "sa:संजय"}; no expansion to name other codes
                for (Iterator<String> it = args.fieldNames(); it.hasNext(); ) {
                    String k = it.next();
                    Matcher m = CODE_FORM.matcher(args.path(k).asText());
                    if (k.chars().allMatch(Character::isDigit) && m.matches() && !m.group(1).equals(ownCode)
                            && languageNames.containsKey(m.group(1))) {
                        addStep(fromTreeTemplates, languageNames.get(m.group(1)), m.group(2));
                    }
                }
            }
        }
        return steps.isEmpty() ? fromTreeTemplates : steps;
    }

    /**
     * The language as the template's expansion names it, for codes langs.tsv has no name for: family codes
     * ("gem" -> "Germanic"), lists ("ru,uk" -> "Russian and Ukrainian") and codes voted "unknown". An expansion
     * reads "[Borrowed from ]language[ word ...]". Null when no name can be read: the step is then dropped
     * rather than shown as a code.
     */
    static String nameFromExpansion(JsonNode args, String expansion) {
        String rest = EXPANSION_PREFIX.matcher(expansion.replaceAll("<[^>]*>", "").trim()).replaceFirst("");
        String word = args.path("4").asText("");
        word = (word.isEmpty() ? args.path("3").asText("") : word).replaceAll("<[^>]*>", "").trim();
        int at = word.isEmpty() || word.equals("-") ? -1 : rest.indexOf(" " + word);
        String name = at > 0 ? rest.substring(0, at) : rest; // a family shows no word: "Germanic"
        if (!LANGUAGE_NAME.matcher(name).matches()) {
            name = rest.replaceFirst(" [\\[(*].*", ""); // "Germanic [Term?]", "Old Iranian *kar-ina-"
        }
        return LANGUAGE_NAME.matcher(name).matches() ? name : null;
    }

    private static void addStep(List<OriginStep> steps, String lang, String rawForm) {
        // every path strips Wiktionary inline modifiers: "Adam<id:first man>", "xōchitl<t:flower>"
        String form = rawForm.replaceAll("<[^>]*>", "").trim();
        if (steps.size() >= MAX_STEPS) {
            return;
        }
        if (!steps.isEmpty() && steps.get(steps.size() - 1).language().equals(lang)) {
            return; // two templates for the same language in a row: keep the first
        }
        steps.add(new OriginStep(lang, form));
    }

    /** The source word as Wiktionary displays it, e.g. "Μῐχᾱήλ (Mĭkhāḗl)"; "" when the template has no word. */
    private static String form(JsonNode args, String expansion, String lang) {
        // strip Wiktionary inline modifiers: "xōchitl<t:flower>" -> "xōchitl"
        String word = args.path("3").asText("").replaceAll("<[^>]*>", "").trim();
        if (word.equals("-")) {
            return "";
        }
        int at = expansion.indexOf(lang + " ");
        if (at >= 0) {
            String shown = expansion.substring(at + lang.length() + 1).replaceAll("<[^>]*>", "").trim();
            // drop the trailing gloss inside the brackets: "संजीत (sañjīt, literally “full victory”)"
            int comma = shown.indexOf(", ");
            int paren = shown.indexOf(" (");
            if (paren > 0 && comma > paren) {
                shown = shown.substring(0, comma) + ")";
            }
            if (!shown.isEmpty()) {
                return shown;
            }
        }
        return word;
    }

    /** "A male given name from Hindi [in turn from Sanskrit]." -> [Hindi, Sanskrit] (newest first). */
    List<OriginStep> chainFromText(String text) {
        List<OriginStep> steps = new ArrayList<>();
        int at = text.indexOf("from ");
        while (at >= 0 && steps.size() < MAX_STEPS) {
            String rest = text.substring(at + 5);
            for (String lang : namesLongestFirst) {
                if (rest.startsWith(lang)) {
                    boolean wordEnds = rest.length() == lang.length() || !Character.isLetter(rest.charAt(lang.length()));
                    boolean seen = steps.stream().anyMatch(s -> s.language().equals(lang));
                    if (wordEnds && !seen) {
                        steps.add(new OriginStep(lang, ""));
                    }
                    break;
                }
            }
            at = text.indexOf("from ", at + 5);
        }
        return steps;
    }

    private static String glossText(JsonNode e) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode sense : e.path("senses")) {
            for (JsonNode g : sense.path("glosses")) {
                if (g.asText().contains("given name")) {
                    sb.append(g.asText()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Glosses of the source word (t/gloss/5 on der/bor/inh-type templates), literal meanings (lit), and quoted
     * glosses in the etymology text. Glosses on affix templates are skipped: they gloss word parts
     * ("who", "like", "God"), not the name.
     */
    private static List<String> meanings(JsonNode e, String display) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode t : e.path("etymology_templates")) {
            JsonNode args = t.path("args");
            if (CHAIN_TEMPLATES.contains(t.path("name").asText())) {
                for (String k : List.of("t", "gloss", "5")) {
                    addMeaning(out, args.path(k).asText(""), display);
                }
            }
            addMeaning(out, args.path("lit").asText(""), display);
        }
        Matcher m = QUOTED.matcher(e.path("etymology_text").asText(""));
        while (m.find()) {
            addMeaning(out, m.group(1), display);
        }
        return new ArrayList<>(out);
    }

    private static void addMeaning(Collection<String> out, String s, String display) {
        String m = s.trim();
        if (m.length() < 2 || m.length() > 80 || m.contains("given name") || m.equalsIgnoreCase(display)) {
            return;
        }
        for (String have : out) {
            if (have.equalsIgnoreCase(m)) {
                return;
            }
        }
        out.add(m);
    }

    /** First prose sentence or two of the etymology; skips the "Etymology tree" diagram lines. */
    static String summary(String text) {
        String[] lines = text.split("\n");
        String prose = "";
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (i == 0 && l.equals("Etymology tree")) {
                continue;
            }
            boolean treeLine = l.split(" ").length < 6 && !l.contains(", ");
            if (!l.isEmpty() && !treeLine) {
                prose = l;
                break;
            }
        }
        if (prose.length() <= MAX_SUMMARY) {
            return prose;
        }
        int cut = prose.lastIndexOf(". ", MAX_SUMMARY);
        while (cut > 60 && isAbbreviation(prose, cut)) {
            cut = prose.lastIndexOf(". ", cut - 1);
        }
        if (cut > 60) {
            return prose.substring(0, cut + 1);
        }
        int end = Character.isLowSurrogate(prose.charAt(MAX_SUMMARY)) ? MAX_SUMMARY - 1 : MAX_SUMMARY;
        return prose.substring(0, end).trim() + "…";
    }

    /** "St. Sophia", "Mt. Sinai", "e.g. x": a period after a word of at most two letters does not end a sentence. */
    private static boolean isAbbreviation(String text, int dot) {
        int start = dot;
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            start--;
        }
        return dot - start <= 2;
    }

    private static String gender(JsonNode e) {
        boolean male = false;
        boolean female = false;
        List<JsonNode> lists = new ArrayList<>();
        lists.add(e.path("categories")); // some entries keep categories at the top level, not per sense
        for (JsonNode sense : e.path("senses")) {
            lists.add(sense.path("categories"));
        }
        for (JsonNode list : lists) {
            for (JsonNode c : list) {
                // the raw dump stores categories as strings; the per-POS files store {"name": ...} objects
                String n = c.path("name").asText(c.asText());
                if (n.contains("unisex given names")) {
                    return "unisex";
                }
                if (n.contains("female given names")) {
                    female = true;
                } else if (n.contains("male given names")) {
                    male = true;
                }
            }
        }
        return male && female ? "unisex" : male ? "male" : female ? "female" : "";
    }

    private static List<String> variants(JsonNode e) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : e.path("forms")) {
            for (JsonNode tag : f.path("tags")) {
                if (tag.asText().equals("alternative")) {
                    out.add(f.path("form").asText());
                }
            }
        }
        return out;
    }
}
