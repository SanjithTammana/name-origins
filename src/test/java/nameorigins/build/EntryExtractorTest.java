package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void chainFromTextReadsNestedFromClauses() {
        assertEquals(List.of(new OriginStep("Hindi", ""), new OriginStep("Sanskrit", "")),
                EXTRACT.chainFromText("A male given name from Hindi [in turn from Sanskrit]."));
    }
}
