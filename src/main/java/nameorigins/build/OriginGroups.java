package nameorigins.build;

import java.util.HashMap;
import java.util.Map;

/**
 * Merges Wiktionary's fine-grained source languages into the origins the guesser predicts. "Biblical Hebrew"
 * and "Hebrew" are one answer to "where does my name come from", and splitting them would halve the training
 * data for both. Languages not listed keep their own name.
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
        group("Germanic", "Proto-Germanic", "Proto-West Germanic", "Frankish", "Old High German", "Old Saxon",
                "Gothic", "Old English", "Old Frisian", "Old Dutch", "Middle High German", "Middle Dutch",
                "Old Franconian", "Lombardic", "Burgundian", "Vandalic");
        group("Old Norse", "Old Norse", "Proto-Norse", "Old Danish", "Old Swedish", "Old Icelandic");
        group("Celtic", "Proto-Celtic", "Old Irish", "Middle Irish", "Irish", "Scottish Gaelic", "Welsh",
                "Old Welsh", "Middle Welsh", "Proto-Brythonic", "Gaulish", "Cornish", "Breton", "Manx");
        group("Sanskrit", "Sanskrit", "Vedic Sanskrit", "Pali", "Prakrit");
        group("Arabic", "Arabic", "Classical Arabic");
        group("Persian", "Persian", "Old Persian", "Middle Persian", "Avestan", "Classical Persian");
        group("Slavic", "Proto-Slavic", "Old Church Slavonic", "Old East Slavic", "Russian", "Polish", "Czech",
                "Serbo-Croatian", "Ukrainian", "Bulgarian", "Slovak", "Slovene", "Macedonian", "Belarusian");
        group("Turkic", "Turkish", "Ottoman Turkish", "Old Turkic", "Proto-Turkic", "Kazakh", "Uzbek",
                "Azerbaijani", "Tatar", "Turkmen", "Kyrgyz");
        group("Etruscan", "Etruscan");
        group("Aramaic", "Aramaic", "Classical Syriac", "Imperial Aramaic", "Jewish Babylonian Aramaic");
        group("Chinese", "Chinese", "Mandarin", "Cantonese", "Middle Chinese", "Old Chinese");
    }

    static String of(String language) {
        return GROUP.getOrDefault(language, language);
    }
}
