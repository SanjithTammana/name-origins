package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides whether a Wiktionary entry is a given name.
 *
 * This is Sanjith's original filter from Assignment3 (testing.java), restructured into the methods he outlined
 * in Etymology.java. His version found categories by scanning the raw line for "name": "..." substrings; here
 * the categories are read from the parsed entry, and the matching rule is unchanged.
 */
public final class NameFilter {
    private NameFilter() {}

    static final String[] FILTERS = {
        "given names",
        "male given names",
        "female given names",
        "unisex given names",
        "diminutives of male given names",
        "diminutives of female given names"
    };

    /** True when the entry is a proper name with at least one given-name category. */
    public static boolean checksNameEntry(JsonNode entry) {
        if (!"name".equals(entry.path("pos").asText())) {
            return false;
        }
        for (JsonNode sense : entry.path("senses")) {
            for (JsonNode category : sense.path("categories")) {
                if (checksCategory(category.path("name").asText(category.asText()))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean checksCategory(String category) {
        return passesRelevantFilter(category, FILTERS);
    }

    /**
     * Keeps "English male given names" and "English given names from Hebrew",
     * but not "English surnames from given names" (a surname derived from a given name).
     */
    static boolean passesRelevantFilter(String area, String[] filters) {
        for (String filter : filters) {
            boolean directMatch = area.endsWith(filter);
            boolean originMatch = area.contains(filter + " from ");
            boolean derivedMatch = area.contains(" from " + filter);
            if ((directMatch && !derivedMatch) || originMatch) {
                return true;
            }
        }
        return false;
    }
}
