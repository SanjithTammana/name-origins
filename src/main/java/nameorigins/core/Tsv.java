package nameorigins.core;

import java.util.ArrayList;
import java.util.List;

/** Minimal tab-separated helpers. Hand-written split avoids regex cost on large files in the browser. */
public final class Tsv {
    private Tsv() {}

    /** Removes characters that would break a TSV row or the chain separators. */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // a surrogate without its partner (e.g. text cut mid-character) cannot be written as UTF-8
            boolean loneHigh = Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)));
            boolean loneLow = Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
            boolean bad = c == '\t' || c == '\n' || c == '\r' || c == '\u001E' || c == '\u001F' || loneHigh || loneLow;
            if (bad && out == null) {
                out = new StringBuilder(s.length()).append(s, 0, i);
            }
            if (out != null) {
                out.append(bad ? ' ' : c);
            }
        }
        return out == null ? s.trim() : out.toString().trim();
    }

    public static String[] split(String line, char sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * Parses a plain decimal like "-9.123" or "4" (no exponent), which is all the data files contain.
     * Float.parseFloat handles every format and was the slow part of loading the model in the browser.
     */
    public static float decimal(String s) {
        int i = 0;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        long whole = 0;
        while (i < s.length() && s.charAt(i) != '.') {
            whole = whole * 10 + (s.charAt(i) - '0');
            i++;
        }
        double v = whole;
        if (i < s.length()) {
            double scale = 0.1;
            for (i++; i < s.length(); i++) {
                v += (s.charAt(i) - '0') * scale;
                scale /= 10;
            }
        }
        return (float) (neg ? -v : v);
    }

    /** Splits text into lines without regex, skipping empty lines. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                if (end > start) {
                    out.add(text.substring(start, end));
                }
                start = i + 1;
            }
        }
        return out;
    }
}
