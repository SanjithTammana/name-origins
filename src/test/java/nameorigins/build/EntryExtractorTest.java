package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against real (trimmed) Wiktionary entries in src/test/resources/fixtures.jsonl. */
class EntryExtractorTest {
    private static final Map<String, JsonNode> FIXTURES = new HashMap<>();
    private static final EntryExtractor EXTRACT = new EntryExtractor(Map.ofEntries(
            Map.entry("en", "English"), Map.entry("enm", "Middle English"), Map.entry("fro", "Old French"),
            Map.entry("ang", "Old English"), Map.entry("la", "Latin"), Map.entry("grc", "Ancient Greek"),
            Map.entry("hbo", "Biblical Hebrew"), Map.entry("hi", "Hindi"), Map.entry("sa", "Sanskrit"),
            Map.entry("ar", "Arabic"), Map.entry("ja", "Japanese"), Map.entry("ine-pro", "Proto-Indo-European")));

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                EntryExtractorTest.class.getResourceAsStream("/fixtures.jsonl"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode e = json.readTree(line);
                FIXTURES.put(e.path("word").asText(), e);
            }
        }
    }

    private static NameEntry entry(String word) {
        return EXTRACT.extract(FIXTURES.get(word)).entry();
    }

    private static List<String> languages(NameEntry e) {
        return e.chain().stream().map(OriginStep::language).toList();
    }

    @Test
    void michaelChainRunsOldestFirst() {
        NameEntry e = entry("Michael");
        assertEquals(List.of("Biblical Hebrew", "Ancient Greek", "Latin", "Old English", "Old French", "Middle English"),
                languages(e));
        assertEquals("מִיכָאֵל (mîḵāʾēl)", e.chain().get(0).form());
        assertEquals("who is like God?", e.meaning());
        assertEquals("male", e.gender());
        assertTrue(e.summary().startsWith("From Middle English Michael, from Old French Michel"), e.summary());
    }

    @Test
    void sanjeetCombinesTemplateMeaningAndSanskritRoot() {
        NameEntry e = entry("Sanjeet");
        assertEquals(List.of("Sanskrit", "Hindi"), languages(e));
        assertEquals("संजीत (sañjīt)", e.chain().get(1).form());
        assertEquals("full victory", e.meaning());
    }

    @Test
    void sanjayUsesTheNewerTreeTemplate() {
        NameEntry e = entry("Sanjay");
        assertEquals(List.of("Sanskrit"), languages(e));
        assertEquals("Sanskrit", e.oldestLanguage());
    }

    @Test
    void meaningsComeFromTemplateArgsAndQuotedGlosses() {
        assertEquals("young green shoot", entry("Chloe").meaning());
        assertEquals("praised, commendable, laudable; to praise, commend, laud, extol", entry("Muhammad").meaning());
        assertTrue(entry("Sophia").meaning().contains("wisdom"), entry("Sophia").meaning());
    }

    @Test
    void rootTemplatesDoNotEnterTheChain() {
        assertEquals(List.of("Ancient Greek"), languages(entry("Chloe")));
    }

    @Test
    void alternativeFormsBecomeVariants() {
        assertTrue(EXTRACT.extract(FIXTURES.get("Muhammad")).variants().contains("Mohammed"));
        assertEquals("muhammad", EXTRACT.extract(FIXTURES.get("Muhammad")).key());
    }

    @Test
    void genderReadsRawDumpStringCategories() throws Exception {
        // the raw dump (what the app is built from) stores categories as plain strings
        JsonNode raw = new ObjectMapper().readTree("""
            {"pos": "name", "word": "Michael", "lang": "English", "lang_code": "en",
             "senses": [{"categories": ["English given names", "English male given names from Hebrew"]}]}""");
        assertEquals("male", EXTRACT.extract(raw).entry().gender());
    }

    @Test
    void genderAlsoReadsTopLevelCategories() throws Exception {
        JsonNode raw = new ObjectMapper().readTree("""
            {"pos": "name", "word": "Sophia", "lang": "English", "lang_code": "en",
             "categories": ["English female given names from Ancient Greek"], "senses": [{"glosses": ["x"]}]}""");
        assertEquals("female", EXTRACT.extract(raw).entry().gender());
    }

    @Test
    void summaryDoesNotEndAtAnAbbreviation() {
        String text = "Chiefly from Latin Sophia, from Ancient Greek Σοφία (Sophía), especially in reference to holy "
                + "wisdom, to the early martyr St. Sophia, and to many figures of European royalty and nobility, "
                + "and a great deal more text that keeps going well past the summary limit so it must be cut somewhere.";
        String s = EntryExtractor.summary(text);
        assertTrue(!s.endsWith("St."), s);
    }

    @Test
    void affixGlossesAndTheNameItselfAreNotMeanings() throws Exception {
        JsonNode e = new ObjectMapper().readTree("""
            {"pos": "name", "word": "Michael", "lang": "Czech", "lang_code": "cs",
             "etymology_templates": [
               {"name": "bor", "args": {"1": "cs", "2": "la", "3": "Michael", "t": "Michael"}},
               {"name": "af", "args": {"1": "hbo", "2": "מִי", "t2": "who", "3": "אֵל", "t": "God"}},
               {"name": "der", "args": {"1": "cs", "2": "hbo", "3": "מִיכָאֵל", "lit": "Who is like God?"}}]}""");
        assertEquals("Who is like God?", EXTRACT.extract(e).entry().meaning());
    }

    @Test
    void inlineModifiersAreStrippedFromForms() throws Exception {
        JsonNode e = new ObjectMapper().readTree("""
            {"pos": "name", "word": "Xóchitl", "lang": "Spanish", "lang_code": "es",
             "etymology_templates": [{"name": "bor", "args": {"1": "es", "2": "nci", "3": "xōchitl<t:flower>"},
               "expansion": "Classical Nahuatl xōchitl<t:flower>"}]}""");
        EntryExtractor x = new EntryExtractor(Map.of("nci", "Classical Nahuatl", "gmh", "Middle High German"));
        assertEquals("xōchitl", x.extract(e).entry().chain().get(0).form());
        JsonNode tree = new ObjectMapper().readTree("""
            {"pos": "name", "word": "Adem", "lang": "German", "lang_code": "de",
             "etymology_templates": [{"name": "etymon", "args": {"1": "de", "2": ":inh", "3": "gmh:Adam<id:first man>"}}]}""");
        assertEquals("Adam", x.extract(tree).entry().chain().get(0).form());
    }

    /** The step the single template in {@code templates} yields, or null when it yields none. */
    private static OriginStep onlyStep(EntryExtractor x, String templates) throws Exception {
        JsonNode e = new ObjectMapper().readTree("""
            {"pos": "name", "word": "X", "lang": "English", "lang_code": "en", "etymology_templates": [%s]}"""
                .formatted(templates));
        List<OriginStep> chain = x.extract(e).entry().chain();
        assertTrue(chain.size() <= 1, chain.toString());
        return chain.isEmpty() ? null : chain.get(0);
    }

    @Test
    void familyCodesTakeTheirNameFromTheExpansion() throws Exception {
        // langs.tsv holds only codes with entries of their own, so no family codes like "gem" or "cel-bry"
        assertEquals(new OriginStep("Germanic", ""), onlyStep(EXTRACT,
                "{\"name\": \"der\", \"args\": {\"1\": \"en\", \"2\": \"gem\", \"3\": \"-\"}, \"expansion\": \"Germanic\"}"));
        assertEquals(new OriginStep("Brythonic", "ardu"), onlyStep(EXTRACT,
                "{\"name\": \"der\", \"args\": {\"1\": \"en\", \"2\": \"cel-bry\", \"3\": \"ardu\"}, \"expansion\": \"Brythonic\"}"));
        assertEquals(new OriginStep("Slavic", "Milan"), onlyStep(EXTRACT,
                "{\"name\": \"bor+\", \"args\": {\"1\": \"hu\", \"2\": \"sla\", \"3\": \"Milan\"}, \"expansion\": \"Borrowed from Slavic\"}"));
    }

    @Test
    void codeListsReadAsTheLanguagesTheyList() throws Exception {
        assertEquals(new OriginStep("Russian and Ukrainian", "А́ня (Ánja)"), onlyStep(EXTRACT,
                "{\"name\": \"bor\", \"args\": {\"1\": \"en\", \"2\": \"ru,uk\", \"3\": \"А́ня\"}, \"expansion\":"
                        + " \"Russian and Ukrainian А́ня (Ánja, informal pet form of А́нна (Ánna, “Ann”))\"}"));
        assertEquals(new OriginStep("Italian, Portuguese, and Spanish", "Adolfo"), onlyStep(EXTRACT,
                "{\"name\": \"bor+\", \"args\": {\"1\": \"en\", \"2\": \"it,pt,es\", \"3\": \"Adolfo\"}, \"expansion\":"
                        + " \"Borrowed from Italian, Portuguese, and Spanish Adolfo\"}"));
    }

    @Test
    void unknownIsNeverALanguage() throws Exception {
        // langs.tsv votes ine-pro to "unknown"; the template's own expansion names it
        EntryExtractor x = new EntryExtractor(Map.of("ine-pro", "unknown", "sa", "Sanskrit"));
        assertEquals(new OriginStep("Proto-Indo-European", "*h₁n̥gʷnís"), onlyStep(x,
                "{\"name\": \"inh\", \"args\": {\"1\": \"iir-pro\", \"2\": \"ine-pro\", \"3\": \"*h₁n̥gʷnís\"},"
                        + " \"expansion\": \"Proto-Indo-European *h₁n̥gʷnís\"}"));
        // tree templates and prose have no expansion to read: the step is dropped, not shown as "unknown"
        assertNull(onlyStep(x, "{\"name\": \"etymon\", \"args\": {\"1\": \"en\", \"2\": \":inh\", \"3\": \"ine-pro:*h₁n̥gʷnís\"}}"));
        assertEquals(List.of(new OriginStep("Sanskrit", "")),
                x.chainFromText("A name from Sanskrit, ultimately from unknown origin."));
        // an expansion that names no language ("substrate", lowercase) drops the step instead of showing a code
        assertNull(onlyStep(x, "{\"name\": \"der\", \"args\": {\"1\": \"en\", \"2\": \"qfa-sub\"}, \"expansion\": \"substrate\"}"));
    }

    @Test
    void chainFromTextReadsNestedFromClauses() {
        assertEquals(List.of(new OriginStep("Hindi", ""), new OriginStep("Sanskrit", "")),
                EXTRACT.chainFromText("A male given name from Hindi [in turn from Sanskrit]."));
    }
}
