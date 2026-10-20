package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nameorigins.core.Text;
import nameorigins.core.Tsv;

/** Derives short, attributed facts from cached Wikipedia given-name articles; never makes network requests. */
public final class WikipediaNames {
    private static final Pattern NAME_PAGE = Pattern.compile("(?is)\\{\\{infobox\\s+(?:given[ _]?name|name)\\b");
    private static final Pattern REF = Pattern.compile("(?is)<ref\\b[^>]*(?:/>|>.*?</ref>)");
    private static final Pattern LINK = Pattern.compile("\\[\\[([^]]+)]]");
    private static final Pattern EXTERNAL = Pattern.compile("\\[https?://[^ ]+ ([^]]+)]]");
    private static final Pattern MEANS = Pattern.compile(
            "(?i)\\b(?:means?|meaning(?: of)?|translates? (?:as|to))\\s+(?:[‘'\"“])([^’'\"”]{2,100})[’'\"”]");
    private static final Pattern WRITING = Pattern.compile("(?m)^\\*\\s*([^,\\n]{1,24}),\\s*[\"“]([^\"”\\n]{2,70})[\"”]");
    private static final Pattern SECTION = Pattern.compile("(?m)^={2,}\\s*([^=]+?)\\s*={2,}\\s*$");
    private static final int BATCH = 20;

    record Fact(String key, String display, String title, String language, String meaning, String summary) {
        String tsv() {
            return String.join("\t", Tsv.clean(key), Tsv.clean(display), Tsv.clean(title),
                    Tsv.clean(language), Tsv.clean(meaning), Tsv.clean(summary));
        }
    }

    private WikipediaNames() {}

    /** A valid source title and an explicit name statement are required before extracting anything. */
    static Fact extract(String display, String title, String wiki) {
        String key = Text.fold(display);
        if (key.isEmpty() || key.length() > 40 || wiki == null || wiki.isBlank()) {
            return null;
        }
        String body = REF.matcher(wiki).replaceAll("").replaceAll("(?s)<!--.*?-->", "");
        String leadSource = body;
        Matcher section = SECTION.matcher(body);
        if (section.find()) {
            leadSource = body.substring(0, section.start());
        }
        String lead = plain(leadSource);
        String titleName = title.replaceFirst("(?i) \\((?:given name|name)\\)$", "");
        if (!Text.fold(titleName).equals(key)
                && !Pattern.compile("(?i)(?<![\\p{L}])" + Pattern.quote(display) + "(?![\\p{L}])")
                        .matcher(lead.substring(0, Math.min(lead.length(), 260))).find()) {
            return null; // a given-name item can still link to a different article (Pher -> Christopher)
        }
        boolean isName = NAME_PAGE.matcher(body).find()
                || lead.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:given name|first name|personal name)\\b.*");
        if (!isName || lead.toLowerCase(Locale.ROOT).contains("may refer to:")) {
            return null;
        }
        String meaning = cleanMeaning(plain(infoboxField(body, "meaning")));
        if (lead.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:multiple origins|many origins|various origins)\\b.*")) {
            meaning = "";
        }
        String language = plain(infoboxField(body, "language"));
        if (language.length() > 60 || !language.matches(".*[\\p{L}].*")) language = "";
        if (meaning.isEmpty()) {
            Matcher m = MEANS.matcher(lead);
            if (m.find()) {
                meaning = cleanMeaning(m.group(1));
            }
        }
        String summary = firstSentences(lead, 2, 300);
        if (summary.startsWith("is ") || summary.startsWith("was ")) summary = display + " " + summary;
        String writings = possibleWritings(body);
        if (!writings.isEmpty()) {
            summary = summary.isEmpty() ? writings : summary + " " + writings;
        }
        if (meaning.isEmpty() && writings.isEmpty() && !hasOriginStatement(lead)) {
            return null;
        }
        return new Fact(key, display, title, language, meaning, summary);
    }

    private static boolean hasOriginStatement(String lead) {
        return Pattern.compile("(?i)\\b(?:of [A-Z][a-z-]+ origin|from the [A-Z][a-z-]+ language|[A-Z][a-z-]+-language (?:given )?name)\\b")
                .matcher(lead).find();
    }

    private static String cleanMeaning(String raw) {
        String meaning = raw.trim().replaceAll("\\s+", " ");
        if (meaning.length() < 2 || meaning.length() > 100
                || meaning.contains(";")
                || meaning.contains("\" or \"")
                || meaning.toLowerCase(Locale.ROOT).matches(".*(?:many|multiple|different|various|depending|depends|varies|unknown|uncertain|see below|not known).*")) {
            return "";
        }
        return meaning.replaceAll("^[‘'\"“]+|[’'\"”]+$", "").replaceAll("[.;,:]+$", "");
    }

    /** The infobox parser only splits on pipes at the outer template level; links and nested templates may contain pipes. */
    static String infoboxField(String wiki, String field) {
        Matcher start = NAME_PAGE.matcher(wiki);
        if (!start.find()) {
            return "";
        }
        int open = start.start();
        int braces = 0;
        int brackets = 0;
        int partStart = -1;
        for (int i = open; i < wiki.length() - 1; i++) {
            String two = wiki.substring(i, i + 2);
            if (two.equals("{{")) { braces++; i++; continue; }
            if (two.equals("}}")) { braces--; if (braces == 0) break; i++; continue; }
            if (two.equals("[[")) { brackets++; i++; continue; }
            if (two.equals("]]")) { brackets--; i++; continue; }
            if (wiki.charAt(i) == '|' && braces == 1 && brackets == 0) {
                if (partStart >= 0) {
                    String found = fieldValue(wiki.substring(partStart, i), field);
                    if (found != null) return found;
                }
                partStart = i + 1;
            }
        }
        if (partStart >= 0) {
            int end = wiki.indexOf("}}", partStart);
            if (end >= 0) {
                String found = fieldValue(wiki.substring(partStart, end), field);
                if (found != null) return found;
            }
        }
        return "";
    }

    private static String fieldValue(String part, String field) {
        int equals = part.indexOf('=');
        if (equals < 0 || !part.substring(0, equals).trim().equalsIgnoreCase(field)) {
            return null;
        }
        return part.substring(equals + 1).trim();
    }

    private static String possibleWritings(String wiki) {
        Matcher headings = SECTION.matcher(wiki);
        while (headings.find()) {
            String heading = headings.group(1).toLowerCase(Locale.ROOT);
            if (!heading.matches(".*(?:written forms|possible writings|kanji|hanja).*")) continue;
            int start = headings.end();
            int end = headings.find() ? headings.start() : wiki.length();
            Matcher rows = WRITING.matcher(wiki.substring(start, end));
            List<String> forms = new ArrayList<>();
            while (rows.find() && forms.size() < 3) {
                String form = plain(rows.group(1));
                String gloss = plain(rows.group(2));
                if (!form.isEmpty() && !gloss.isEmpty()) forms.add(form + " (“" + gloss + "”)");
            }
            if (!forms.isEmpty()) {
                return "Possible writings include " + String.join(", ", forms) + ".";
            }
        }
        return "";
    }

    private static String firstSentences(String lead, int limit, int maxChars) {
        String s = lead.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        for (String marker : List.of("Notable people with", "People with the name include", "Notable bearers", "People named ")) {
            int at = s.indexOf(marker);
            if (at >= 0) s = s.substring(0, at).trim();
        }
        if (s.contains("()") || s.contains("( )")) {
            int period = s.indexOf('.');
            if (period >= 0) s = s.substring(0, period + 1);
        }
        int end = 0;
        Matcher sentence = Pattern.compile("[.!?](?=\\s+[A-Z]|$)").matcher(s);
        for (int i = 0; i < limit && sentence.find(); i++) {
            if (sentence.end() > maxChars) break;
            end = sentence.end();
        }
        if (end == 0) end = Math.min(s.length(), maxChars);
        String result = s.substring(0, end).trim();
        if (end < s.length() && !result.endsWith(".")) {
            int space = result.lastIndexOf(' ');
            result = (space > 0 ? result.substring(0, space) : result) + ".";
        }
        return result;
    }

    /** Small wiki-markup cleaner for short fields and leads. Unrecognised templates are omitted. */
    static String plain(String wiki) {
        String s = REF.matcher(wiki).replaceAll("");
        s = s.replaceAll("(?is)\\[\\[(?:File|Image):[^]]+]]", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "; ");
        s = EXTERNAL.matcher(s).replaceAll("$1");
        Matcher links = LINK.matcher(s);
        StringBuffer linked = new StringBuffer();
        while (links.find()) {
            String[] parts = links.group(1).split("\\|", -1);
            links.appendReplacement(linked, Matcher.quoteReplacement(parts[parts.length - 1]));
        }
        links.appendTail(linked);
        s = linked.toString();
        for (int pass = 0; pass < 8 && s.contains("{{"); pass++) {
            String replaced = s.replaceAll("\\{\\{([^{}]*)}}", "");
            if (replaced.equals(s)) break;
            s = replaced;
        }
        return s.replace("'''", "").replace("''", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^\\s*\\|[^\\n]*$", "").replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\s+", " ").replaceAll("\\s+,", ",").trim();
    }

    /** Writes a reproducible, compact TSV from the cached batches. The source batches remain for later review. */
    public static void run(Path work) throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<WikipediaPull.Article> articles = WikipediaPull.loadArticles(work);
        Path output = work.resolve("wikipedia.tsv");
        int selected = 0;
        int withMeaning = 0;
        try (BufferedWriter out = Files.newBufferedWriter(output)) {
            for (int start = 0; start < articles.size(); start += BATCH) {
                List<WikipediaPull.Article> batch = articles.subList(start, Math.min(articles.size(), start + BATCH));
                Path file = WikipediaPull.batchFile(work, batch, start / BATCH);
                if (!Files.exists(file)) throw new IOException("Missing full-article cache " + file);
                JsonNode query = json.readTree(file.toFile()).path("query");
                Map<String, String> titleMap = new HashMap<>();
                for (String field : List.of("normalized", "redirects")) {
                    for (JsonNode rename : query.path(field)) {
                        titleMap.put(rename.path("from").asText(), rename.path("to").asText());
                    }
                }
                Map<String, JsonNode> pages = new HashMap<>();
                for (JsonNode page : query.path("pages")) {
                    pages.put(page.path("title").asText(), page);
                }
                for (WikipediaPull.Article a : batch) {
                    String title = a.title();
                    for (int hop = 0; hop < 3 && titleMap.containsKey(title); hop++) title = titleMap.get(title);
                    JsonNode page = pages.get(title);
                    if (page == null) continue;
                    String wiki = page.path("revisions").path(0).path("slots").path("main").path("content").asText("");
                    Fact fact = extract(a.label(), page.path("title").asText(a.title()), wiki);
                    if (fact == null) continue;
                    out.write(fact.tsv());
                    out.write('\n');
                    selected++;
                    if (!fact.meaning().isEmpty()) withMeaning++;
                }
            }
        }
        System.out.printf("Wikipedia facts: %,d articles, %,d with an explicit meaning%n", selected, withMeaning);
    }

    public static void main(String[] args) throws IOException {
        run(Path.of(args.length > 0 ? args[0] : "work"));
    }
}
