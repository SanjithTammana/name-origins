package nameorigins.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text normalization shared by the offline tools and the browser.
 * One implementation on both sides means a key built offline always matches a key typed in the page.
 * (java.text.Normalizer is not available under TeaVM, so accents are folded with a lookup table.)
 */
public final class Text {
    private Text() {}

    // Lowercase Latin letter -> plain spelling, generated from Unicode decompositions (U+00C0-U+024F and
    // U+1E00-U+1EFF: Yoruba ọ, Romanian ș, pinyin ǎ, Vietnamese ế, ...) plus letters that have none (ø, ł, þ, ß).
    // Each space-separated item is one letter followed by its replacement.
    private static final String PAIRS = ""
            + "ßss àa áa âa ãa äa åa æae çc èe ée êe ëe ìi íi îi ïi ðd ñn òo óo ôo õo öo øo ùu úu "
            + "ûu üu ýy þth ÿy āa ăa ąa ćc ĉc ċc čc ďd đd ēe ĕe ėe ęe ěe ĝg ğg ġg ģg ĥh ħh ĩi īi "
            + "ĭi įi ıi ĳij ĵj ķk ĺl ļl ľl ŀl łl ńn ņn ňn ŉn ŋng ōo ŏo őo œoe ŕr ŗr řr śs ŝs şs "
            + "šs ţt ťt ŧt ũu ūu ŭu ůu űu ųu ŵw ŷy źz żz žz ſs ƀb ƈc ƙk ƚl ơo ưu ƴy ƶz ǆdz ǉlj "
            + "ǌnj ǎa ǐi ǒo ǔu ǖu ǘu ǚu ǜu ǟa ǡa ǣae ǥg ǧg ǩk ǫo ǭo ǯz ǰj ǳdz ǵg ǹn ǻa ǽae ǿo ȁa "
            + "ȃa ȅe ȇe ȉi ȋi ȍo ȏo ȑr ȓr ȕu ȗu șs țt ȟh ȥz ȧa ȩe ȫo ȭo ȯo ȱo ȳy ɍr ɏy ḁa ḃb "
            + "ḅb ḇb ḉc ḋd ḍd ḏd ḑd ḓd ḕe ḗe ḙe ḛe ḝe ḟf ḡg ḣh ḥh ḧh ḩh ḫh ḭi ḯi "
            + "ḱk ḳk ḵk ḷl ḹl ḻl ḽl ḿm ṁm ṃm ṅn ṇn ṉn ṋn ṍo ṏo ṑo ṓo ṕp ṗp ṙr ṛr "
            + "ṝr ṟr ṡs ṣs ṥs ṧs ṩs ṫt ṭt ṯt ṱt ṳu ṵu ṷu ṹu ṻu ṽv ṿv ẁw ẃw ẅw ẇw "
            + "ẉw ẋx ẍx ẏy ẑz ẓz ẕz ẖh ẗt ẘw ẙy ẛs ẜs ẝs ạa ảa ấa ầa ẩa ẫa ậa ắa "
            + "ằa ẳa ẵa ặa ẹe ẻe ẽe ếe ềe ểe ễe ệe ỉi ịi ọo ỏo ốo ồo ổo ỗo ộo ớo "
            + "ờo ởo ỡo ợo ụu ủu ứu ừu ửu ữu ựu ỳy ỵy ỷy ỹy";
    private static final String[] LATIN = new String[0x250];
    private static final String[] LATIN_ADDITIONAL = new String[0x100]; // U+1E00-U+1EFF

    static {
        int i = 0;
        while (i < PAIRS.length()) {
            int end = PAIRS.indexOf(' ', i);
            if (end < 0) {
                end = PAIRS.length();
            }
            char c = PAIRS.charAt(i);
            String to = PAIRS.substring(i + 1, end);
            if (c < 0x250) {
                LATIN[c] = to;
            } else {
                LATIN_ADDITIONAL[c - 0x1E00] = to;
            }
            i = end + 1;
        }
    }

    private static final Set<String> STOP_WORDS = new HashSet<>(List.of(
            "a", "an", "and", "the", "of", "or", "in", "to", "from", "for", "with", "by", "as", "is", "was",
            "be", "which", "that", "this", "it", "its", "on", "at", "also", "especially", "literally", "name",
            "given", "male", "female", "unisex", "form", "forms", "variant", "used", "use", "meaning", "means",
            "ultimately", "probably", "possibly", "perhaps", "via", "into", "one", "who", "his", "her", "he",
            "she", "their", "they", "are", "were", "been", "has", "have", "had", "not", "but", "see", "etc"));

    /**
     * Lowercases, folds Latin accents, drops combining accents (U+0300-U+036F, e.g. the stress marks in
     * Wiktionary's "Ива́н"), trims. Other non-Latin letters pass through unchanged. Locale.ROOT, so a build on a
     * Turkish-locale machine does not turn "I" into dotless "ı".
     */
    public static String fold(String s) {
        String lower = s.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = null;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            String to = c < 0xC0 ? null
                    : c < 0x250 ? LATIN[c]
                    : c >= 0x1E00 && c <= 0x1EFF ? LATIN_ADDITIONAL[c - 0x1E00]
                    : c >= 0x300 && c <= 0x36F ? "" : null;
            if (to != null) {
                if (out == null) {
                    out = new StringBuilder(lower.length() + 2).append(lower, 0, i);
                }
                out.append(to);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? lower : out.toString().trim();
    }

    /** Splits meaning text into content words: folded, stop words removed, simple plural stripped. */
    public static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        String f = fold(s);
        int start = -1;
        for (int i = 0; i <= f.length(); i++) {
            boolean letter = i < f.length() && Character.isLetter(f.charAt(i));
            if (letter && start < 0) {
                start = i;
            } else if (!letter && start >= 0) {
                addToken(tokens, f.substring(start, i));
                start = -1;
            }
        }
        return tokens;
    }

    private static void addToken(List<String> tokens, String w) {
        if (w.length() < 2 || STOP_WORDS.contains(w)) {
            return;
        }
        tokens.add(stem(w));
    }

    /** Plural stripping only; anything stronger merges unrelated meaning words. */
    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** "anne-marie" -> "Anne-Marie": the display a key gets when the directory stores none. */
    public static String defaultDisplay(String key) {
        StringBuilder out = new StringBuilder(key.length());
        boolean start = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-';
        }
        return out.toString();
    }

    /** True for a single Unicode letter in a Latin-like range where sound keys make sense. */
    public static boolean isLatinWord(String folded) {
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c > 0x24F) {
                return false;
            }
        }
        return true;
    }
}
