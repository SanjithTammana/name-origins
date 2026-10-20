package nameorigins.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Merges Wiktionary's fine-grained source languages into the origins the guesser predicts. "Biblical Hebrew"
 * and "Hebrew" are one answer to "where does my name come from", and splitting them would halve the training
 * data for both. Languages not listed keep their own name. Wiktionary family names ("Germanic", "Slavic") join
 * their group, and so does a list ("Russian and Ukrainian") whose languages all share one.
 */
final class OriginGroups {
    private OriginGroups() {}

    private static final Map<String, String> GROUP = new HashMap<>();

    private static void group(String to, String... from) {
        for (String f : from) {
            GROUP.put(f, to);
        }
    }

    static {
        group("Hebrew", "Hebrew", "Biblical Hebrew", "Medieval Hebrew");
        group("Greek", "Ancient Greek", "Koine Greek", "Byzantine Greek", "Greek", "Mycenaean Greek");
        group("Latin", "Latin", "Late Latin", "Medieval Latin", "New Latin", "Old Latin", "Ecclesiastical Latin",
                "Vulgar Latin");
        group("Germanic", "Germanic", "West Germanic", "Low Franconian", "Frisian", "Proto-Germanic", "Proto-West Germanic", "Frankish", "Old High German", "Old Saxon",
                "Gothic", "Old English", "Old Frisian", "Old Dutch", "Middle High German", "Middle Dutch",
                "Old Franconian", "Lombardic", "Burgundian", "Vandalic");
        group("Old Norse", "Old Norse", "Proto-Norse", "Old Danish", "Old Swedish", "Old Icelandic");
        group("Celtic", "Celtic", "Brythonic", "Goidelic", "Proto-Celtic", "Old Irish", "Middle Irish", "Irish", "Scottish Gaelic", "Welsh",
                "Old Welsh", "Middle Welsh", "Proto-Brythonic", "Gaulish", "Cornish", "Breton", "Manx");
        group("Sanskrit", "Sanskrit", "Vedic Sanskrit", "Pali", "Prakrit", "Indo-Aryan", "Middle Indo-Aryan");
        group("Arabic", "Arabic", "Classical Arabic");
        group("Persian", "Persian", "Old Persian", "Middle Persian", "Avestan", "Classical Persian", "Iranian Persian",
                "Old Iranian", "Median");
        group("Slavic", "Slavic", "East Slavic", "West Slavic", "South Slavic", "Proto-Slavic", "Old Church Slavonic", "Old East Slavic", "Russian", "Polish", "Czech",
                "Serbo-Croatian", "Ukrainian", "Bulgarian", "Slovak", "Slovene", "Macedonian", "Belarusian");
        group("Turkic", "Turkic", "Turkish", "Ottoman Turkish", "Old Turkic", "Proto-Turkic", "Kazakh", "Uzbek",
                "Azerbaijani", "Tatar", "Turkmen", "Kyrgyz");
        group("Etruscan", "Etruscan");
        group("Aramaic", "Aramaic", "Classical Syriac", "Imperial Aramaic", "Jewish Babylonian Aramaic");
        group("Chinese", "Sinitic", "Chinese", "Mandarin", "Cantonese", "Middle Chinese", "Old Chinese");
    }

    static String of(String language) {
        String to = GROUP.get(language);
        if (to == null && language.contains(" and ")) {
            // "Bulgarian, Macedonian, and Russian"; a name like "Antigua and Barbuda Creole English" has no group
            Set<String> groups = new HashSet<>();
            for (String part : language.split(",? and |, ")) {
                groups.add(GROUP.get(part));
            }
            to = groups.size() == 1 ? groups.iterator().next() : null;
        }
        return to == null ? language : to;
    }
}
