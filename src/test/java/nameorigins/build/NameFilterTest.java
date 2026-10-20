package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NameFilterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsGivenNameCategories() {
        assertTrue(NameFilter.checksCategory("English male given names"));
        assertTrue(NameFilter.checksCategory("English given names"));
        assertTrue(NameFilter.checksCategory("English female given names from Hebrew"));
        assertTrue(NameFilter.checksCategory("German diminutives of female given names"));
    }

    @Test
    void rejectsSurnamesDerivedFromGivenNames() {
        assertFalse(NameFilter.checksCategory("English surnames from given names"));
        assertFalse(NameFilter.checksCategory("English place names"));
    }

    @Test
    void readsCategoriesFromParsedEntry() throws Exception {
        String keep = """
            {"pos": "name", "word": "Michael", "senses": [{"categories": [
              {"name": "Ancient Greek links with redundant target parameters"},
              {"name": "English male given names"}]}]}""";
        String surname = """
            {"pos": "name", "word": "Johnson", "senses": [{"categories": [
              {"name": "English surnames from given names"}]}]}""";
        String noun = """
            {"pos": "noun", "word": "michael", "senses": [{"categories": [{"name": "English male given names"}]}]}""";
        assertTrue(NameFilter.checksNameEntry(JSON.readTree(keep)));
        assertFalse(NameFilter.checksNameEntry(JSON.readTree(surname)));
        assertFalse(NameFilter.checksNameEntry(JSON.readTree(noun)));
    }
}
