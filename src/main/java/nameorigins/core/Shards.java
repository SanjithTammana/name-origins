package nameorigins.core;

/**
 * Entry details are split into one file per first letter, so a lookup downloads ~1/26 of the data.
 * Keys starting with anything other than a-z share the "_" shard.
 */
public final class Shards {
    private Shards() {}

    public static String of(String key) {
        char c = key.isEmpty() ? '_' : key.charAt(0);
        return c >= 'a' && c <= 'z' ? String.valueOf(c) : "_";
    }

    public static String file(String key) {
        return "data/entries/" + of(key) + ".tsv";
    }
}
