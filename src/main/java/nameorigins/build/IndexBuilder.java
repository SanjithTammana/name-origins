package nameorigins.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import nameorigins.core.NameEntry;
import nameorigins.core.Shards;
import nameorigins.core.Text;
import nameorigins.core.Tsv;
import nameorigins.core.UnionFind;

/**
 * Builds the browser data from the work files:
 *   site/data/directory.tsv      every name: key, display, variant group, state (2 origin, 1 entries, 0 neither)
 *   site/data/entries/<x>.tsv    entry details per first letter
 *   work/origins.tsv             key, display, oldest language  (training data for the origin model)
 *   work/meanings.tsv            key, display, meaning text     (documents for meaning search)
 */
public final class IndexBuilder {
    private static final int MAX_ENTRIES_PER_NAME = 8;
    private static final int MAX_KEY_LENGTH = 40;

    /** Everything known about one folded name. */
    private static final class NameData {
        String display;
        final List<NameEntry> entries = new ArrayList<>();
        final Set<String> variants = new LinkedHashSet<>();
        final Set<String> meanings = new LinkedHashSet<>();
        final Set<String> wikidataLanguages = new LinkedHashSet<>();
    }

    private final Map<String, NameData> names = new TreeMap<>();

    public static void run(Path work, Path site) throws IOException {
        IndexBuilder b = new IndexBuilder();
        Map<String, String> langs = LanguageNames.load(work.resolve("langs.tsv"));
        b.readWiktionary(work.resolve("names.jsonl"), new EntryExtractor(langs));
        Path wikidata = work.resolve("wikidata.tsv");
        if (Files.exists(wikidata)) {
            b.readWikidata(wikidata);
        } else {
            System.out.println("  no wikidata.tsv: building from Wiktionary only");
        }
        b.addVariantOnlyNames();
        b.write(work, site);
    }

    private NameData data(String key, String display) {
        NameData d = names.computeIfAbsent(key, k -> new NameData());
        if (d.display == null) {
            d.display = display;
        }
        return d;
    }

    private static boolean usableKey(String key) {
        return !key.isEmpty() && key.length() <= MAX_KEY_LENGTH;
    }

    void readWiktionary(Path namesJsonl, EntryExtractor extractor) throws IOException {
        ObjectMapper json = new ObjectMapper();
        long n = 0;
        try (BufferedReader in = Files.newBufferedReader(namesJsonl)) {
            String line;
            while ((line = in.readLine()) != null) {
                EntryExtractor.Extracted x = extractor.extract(json.readTree(line));
                if (!usableKey(x.key())) {
                    continue;
                }
                NameData d = data(x.key(), x.entry().display());
                d.entries.add(x.entry());
                d.variants.addAll(x.variants());
                d.meanings.addAll(x.meanings());
                n++;
            }
        }
        System.out.printf("  wiktionary: %,d entries under %,d names%n", n, names.size());
    }

    void readWikidata(Path tsv) throws IOException {
        int added = 0;
        for (String line : Files.readAllLines(tsv)) {
            String[] f = Tsv.split(line, '\t');
            String key = Text.fold(f[0]);
            if (!usableKey(key)) {
                continue;
            }
            boolean isNew = !names.containsKey(key);
            NameData d = data(key, f[0]);
            if (isNew) {
                added++;
            }
            if (f.length > 1 && !f[1].isEmpty() && !f[1].equals("multiple languages")) {
                d.wikidataLanguages.add(f[1]);
            }
            if (f.length > 2 && !f[2].isEmpty()) {
                d.variants.add(f[2]);
            }
        }
        System.out.printf("  wikidata: %,d names not in Wiktionary%n", added);
    }

    /** A recorded variant spelling with no entry of its own still becomes a name, so typing it finds the group. */
    void addVariantOnlyNames() {
        List<String[]> pending = new ArrayList<>();
        for (Map.Entry<String, NameData> e : names.entrySet()) {
            for (String v : e.getValue().variants) {
                String key = Text.fold(v);
                if (usableKey(key) && !names.containsKey(key)) {
                    pending.add(new String[] {key, v});
                }
            }
        }
        for (String[] p : pending) {
            data(p[0], p[1]);
        }
        System.out.printf("  %,d variant-only spellings added%n", pending.size());
    }

    void write(Path work, Path site) throws IOException {
        List<String> keys = new ArrayList<>(names.keySet()); // TreeMap: already sorted
        Map<String, Integer> slot = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            slot.put(keys.get(i), i);
        }
        UnionFind groups = new UnionFind(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            for (String v : names.get(keys.get(i)).variants) {
                Integer j = slot.get(Text.fold(v));
                if (j != null) {
                    groups.union(i, j);
                }
            }
        }

        Path entriesDir = site.resolve("data").resolve("entries");
        Files.createDirectories(entriesDir);
        Map<String, BufferedWriter> shards = new HashMap<>();
        int withOrigin = 0;
        int withEntries = 0;
        try (BufferedWriter dir = Files.newBufferedWriter(site.resolve("data").resolve("directory.tsv"));
                BufferedWriter origins = Files.newBufferedWriter(work.resolve("origins.tsv"));
                BufferedWriter meanings = Files.newBufferedWriter(work.resolve("meanings.tsv"))) {
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                NameData d = names.get(key);
                List<NameEntry> entries = ordered(d);
                boolean hasOrigin = entries.stream().anyMatch(e -> !e.chain().isEmpty());
                dir.write(String.join("\t", key, Tsv.clean(d.display),
                        String.valueOf(groups.find(i)), hasOrigin ? "2" : entries.isEmpty() ? "0" : "1"));
                dir.newLine();
                if (!entries.isEmpty()) {
                    withEntries++;
                    BufferedWriter shard = shards.computeIfAbsent(Shards.of(key), s -> open(entriesDir, s));
                    for (NameEntry e : entries) {
                        shard.write(e.toTsv(key));
                        shard.newLine();
                    }
                }
                if (hasOrigin) {
                    withOrigin++;
                    NameEntry best = entries.stream().filter(e -> !e.chain().isEmpty()).findFirst().orElseThrow();
                    origins.write(key + "\t" + Tsv.clean(d.display) + "\t" + best.oldestLanguage());
                    origins.newLine();
                }
                if (!d.meanings.isEmpty()) {
                    meanings.write(key + "\t" + Tsv.clean(d.display) + "\t" + Tsv.clean(String.join("; ", d.meanings)));
                    meanings.newLine();
                }
            }
        } finally {
            for (BufferedWriter w : shards.values()) {
                w.close();
            }
        }
        System.out.printf("  directory: %,d names, %,d with entries, %,d with an origin chain%n",
                keys.size(), withEntries, withOrigin);
    }

    private static BufferedWriter open(Path dir, String shard) {
        try {
            return Files.newBufferedWriter(dir.resolve(shard + ".tsv"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** English first, then entries with an origin chain, then by language; Wikidata line if nothing else. */
    private static List<NameEntry> ordered(NameData d) {
        List<NameEntry> list = new ArrayList<>(d.entries);
        list.sort(Comparator
                .comparing((NameEntry e) -> !e.language().equals("English"))
                .thenComparing(e -> e.chain().isEmpty())
                .thenComparing(NameEntry::language));
        if (list.size() > MAX_ENTRIES_PER_NAME) {
            list = new ArrayList<>(list.subList(0, MAX_ENTRIES_PER_NAME));
        }
        if (list.isEmpty() && !d.wikidataLanguages.isEmpty()) {
            String langs = String.join(", ", d.wikidataLanguages);
            list.add(new NameEntry(d.display, langs, "", "",
                    "Listed on Wikidata as a given name in " + langs + ". No etymology is recorded for it yet.",
                    List.of()));
        }
        return list;
    }

    public static void main(String[] args) throws IOException {
        run(Path.of(args.length > 0 ? args[0] : "work"), Path.of(args.length > 1 ? args[1] : "site"));
    }
}
