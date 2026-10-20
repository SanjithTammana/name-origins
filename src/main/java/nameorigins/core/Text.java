package nameorigins.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Text normalization shared by the offline tools and the browser.
 * One implementation on both sides means a key built offline always matches a key typed in the page.
 * (java.text.Normalizer is not available under TeaVM, so accents are folded with a lookup table.)
 */
public final class Text {
    private Text() {}

    // Each char in FROM maps to the string at the same index in TO.
    private static final String FROM =
            "àáâãäåāăąçćĉċčďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňòóôõöøōŏőŕŗřśŝşšţťŧùúûüũūŭůűųŵýÿŷźżžðþßæœ";
    private static final String[] TO = {
        "a", "a", "a", "a", "a", "a", "a", "a", "a", "c", "c", "c", "c", "c", "d", "d", "e", "e", "e", "e", "e",
        "e", "e", "e", "e", "g", "g", "g", "g", "h", "h", "i", "i", "i", "i", "i", "i", "i", "i", "i", "j", "k",
        "l", "l", "l", "l", "l", "n", "n", "n", "n", "o", "o", "o", "o", "o", "o", "o", "o", "o", "r", "r", "r",
        "s", "s", "s", "s", "t", "t", "t", "u", "u", "u", "u", "u", "u", "u", "u", "u", "u", "w", "y", "y", "y",
        "z", "z", "z", "d", "th", "ss", "ae", "oe"
    };

    private static final Set<String> STOP_WORDS = new HashSet<>(List.of(
            "a", "an", "and", "the", "of", "or", "in", "to", "from", "for", "with", "by", "as", "is", "was",
            "be", "which", "that", "this", "it", "its", "on", "at", "also", "especially", "literally", "name",
            "given", "male", "female", "unisex", "form", "forms", "variant", "used", "use", "meaning", "means",
            "ultimately", "probably", "possibly", "perhaps", "via", "into", "one", "who", "his", "her", "he",
            "she", "their", "they", "are", "were", "been", "has", "have", "had", "not", "but", "see", "etc"));

    /** Lowercases, folds Latin accents, trims. Non-Latin scripts pass through unchanged. */
    public static String fold(String s) {
        String lower = s.trim().toLowerCase();
        StringBuilder out = null;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int at = c < 0xC0 ? -1 : FROM.indexOf(c);
            if (at >= 0) {
                if (out == null) {
                    out = new StringBuilder(lower.length() + 2).append(lower, 0, i);
                }
                out.append(TO[at]);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? lower : out.toString();
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
