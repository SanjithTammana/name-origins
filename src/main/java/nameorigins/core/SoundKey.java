package nameorigins.core;

/**
 * Sound keys for transliterated names. Two spellings that a person would say the same way should get the
 * same key, so "Sanjith", "Sanjeet" and "Sanjit" all become "sanjit".
 *
 * strong: merges spelling choices that do not change the sound (th/t, ph/f, ee/i, c/k, doubled letters, final h).
 * skeleton: the strong key with vowels removed after the first letter, for vowel-only differences
 *           (Mohammed / Muhammad). Looser, so its matches are ranked by edit distance before use.
 * Both expect input already passed through {@link Text#fold}.
 *
 * One left-to-right pass over the letters. (An earlier version applied 14 String.replace calls in a row; for
 * 62k names at page load that took about 2 s in the browser.)
 */
public final class SoundKey {
    private SoundKey() {}

    public static String strong(String folded) {
        int n = folded.length();
        StringBuilder s = new StringBuilder(n + 2);
        for (int i = 0; i < n; i++) {
            char c = folded.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            char d = i + 1 < n ? folded.charAt(i + 1) : 0;
            if (d == 'h' && (c == 'p' || c == 't' || c == 'd' || c == 'b' || c == 'k' || c == 'g')) {
                s.append(c == 'p' ? 'f' : c); // ph -> f; th, dh, bh, kh, gh -> t, d, b, k, g
                i++;
            } else if (c == 'c' && d == 'k') {
                s.append('k');
                i++;
            } else if (c == 'e' && d == 'e') {
                s.append('i');
                i++;
            } else if (c == 'o' && (d == 'o' || d == 'u')) {
                s.append('u');
                i++;
            } else if (c == 'q') {
                s.append('k');
            } else if (c == 'x') {
                s.append("ks");
            } else if (c == 'w') {
                s.append('v');
            } else if (c == 'y') {
                s.append('i');
            } else if (c == 'c' && d != 'h') {
                s.append('k'); // c before anything except h sounds like k (Catherine/Katherine); "ch" stays
            } else {
                s.append(c);
            }
        }
        StringBuilder out = new StringBuilder(s.length());
        int last = s.length() - 1;
        for (int i = 0; i <= last; i++) {
            char c = s.charAt(i);
            if (c == 'h' && i == last && i > 0 && isVowel(s.charAt(i - 1))) {
                continue; // final h after a vowel is silent (Sarah/Sara, Aishah/Aisha)
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) == c) {
                continue; // doubled letters collapse (Matthew/Mathew, Anna/Ana)
            }
            out.append(c);
        }
        return out.toString();
    }

    public static String skeleton(String folded) {
        return skeletonOfStrong(strong(folded));
    }

    /** Skeleton from an already computed strong key, so callers holding both do the work once. */
    public static String skeletonOfStrong(String s) {
        if (s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder().append(s.charAt(0));
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isVowel(c) && out.charAt(out.length() - 1) != c) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }
}
