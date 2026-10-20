package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the page assumes about the committed files in site/data. The page parses them with hand-written code
 * that does no validation (binary search on sorted keys, fixed column positions), so a broken invariant here
 * shows up in the browser as a wrong or blank result rather than an error.
 */
class DataContractTest {

    /** Collects violations so one run reports many, not just the first. */
    private static final class Problems {
        private final String what;
        private final List<String> found = new ArrayList<>();
        private int count;
        private String summary = "";

        Problems(String what) {
            this.what = what;
        }

        void add(String p) {
            count++;
            if (found.size() < 20) {
                found.add(p);
            }
        }

        void check() {
            if (count > 0) {
                fail(count + " " + what + summary + ", e.g.\n  " + String.join("\n  ", found));
            }
        }
    }

    private record Row(String key, String display, int group, int state) {}

    private static List<Row> directoryRows() {
        List<Row> rows = new ArrayList<>();
        for (String line : SiteData.lines("directory.tsv")) {
            String[] f = Tsv.split(line, '\t');
            if (f.length != 4) {
                fail("directory.tsv row without exactly 4 columns: " + line);
            }
            rows.add(new Row(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3])));
        }
        return rows;
    }

    private static Set<String> directoryKeys() {
        Set<String> keys = new HashSet<>();
        for (Row r : directoryRows()) {
            keys.add(r.key());
        }
        return keys;
    }

    @Test
    void directoryIsStrictlySortedWithFourColumnsAndKnownStates() {
        List<Row> rows = directoryRows();
        assertTrue(rows.size() > 100_000, "directory looks truncated: " + rows.size());
        Problems p = new Problems("directory rows out of order, duplicated or malformed");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0 && rows.get(i - 1).key().compareTo(r.key()) >= 0) {
                p.add("'" + rows.get(i - 1).key() + "' then '" + r.key() + "'");
            }
            if (r.state() < 0 || r.state() > 2) {
                p.add(r.key() + ": state " + r.state());
            }
            // the group id is the union-find root, i.e. the slot of one of the group's own members
            if (r.group() < 0 || r.group() >= rows.size() || rows.get(r.group()).group() != r.group()) {
                p.add(r.key() + ": group " + r.group() + " is not the slot of a member");
            }
        }
        p.check();
        // NameDirectory.parse reads the same rows by position; it must agree with the plain split above
        NameDirectory dir = SiteData.directory();
        assertEquals(rows.size(), dir.size());
        for (int i = 0; i < rows.size(); i += 997) {
            Row r = rows.get(i);
            assertEquals(r.key(), dir.key(i));
            assertEquals(i, dir.slot(r.key()));
            assertEquals(r.state() == 2, dir.hasOrigin(i), r.key());
            assertEquals(r.state() >= 1, dir.hasEntries(i), r.key());
        }
    }

    @Test
    void keysAreWhatFoldingATypedNameProduces() {
        // the page looks a typed name up as Text.fold(typed); a key that is not its own fold can never be found
        Problems p = new Problems("keys that no typed name can reach");
        for (Row r : directoryRows()) {
            String k = r.key();
            if (k.isEmpty() || !Text.fold(k).equals(k)) {
                p.add("'" + k + "' folds to '" + Text.fold(k) + "'");
            }
        }
        p.check();
    }

    @Test
    void storedDisplayIsOnlyWrittenWhenItDiffersFromTheDefault() {
        Problems p = new Problems("display columns that are redundant or fold to a different key");
        for (Row r : directoryRows()) {
            if (r.display().isEmpty()) {
                continue;
            }
            if (r.display().equals(Text.defaultDisplay(r.key()))) {
                p.add(r.key() + ": display '" + r.display() + "' equals the default");
            }
            if (!Text.fold(r.display()).equals(r.key())) {
                p.add(r.key() + ": display '" + r.display() + "' folds to '" + Text.fold(r.display()) + "'");
            }
        }
        p.check();
    }

    @Test
    void entriesExistExactlyForNamesWithStateOneOrTwo() {
        Map<String, List<NameEntry>> entries = SiteData.entries();
        Problems p = new Problems("directory states that disagree with the entry shards");
        Set<String> keys = new HashSet<>();
        for (Row r : directoryRows()) {
            keys.add(r.key());
            List<NameEntry> es = entries.getOrDefault(r.key(), List.of());
            if (r.state() >= 1 && es.isEmpty()) {
                p.add(r.key() + ": state " + r.state() + " but no entry rows");
            }
            if (r.state() == 0 && !es.isEmpty()) {
                p.add(r.key() + ": state 0 but " + es.size() + " entry rows");
            }
            boolean anyChain = es.stream().anyMatch(e -> !e.chain().isEmpty());
            if (r.state() == 2 && !anyChain) {
                p.add(r.key() + ": state 2 but no entry has a chain");
            }
            if (r.state() == 1 && anyChain) {
                p.add(r.key() + ": state 1 but an entry has a chain");
            }
        }
        for (String k : entries.keySet()) {
            if (!keys.contains(k)) {
                p.add("entry rows for '" + k + "', which is not in directory.tsv");
            }
        }
        p.check();
    }

    @Test
    void everyEntryRowIsInItsOwnShardAndRoundTrips() {
        Problems p = new Problems("entry rows that are misplaced or do not round-trip");
        int rows = 0;
        for (String file : SiteData.shardFiles()) {
            String shard = file.substring("entries/".length(), file.length() - ".tsv".length());
            for (String line : SiteData.lines(file)) {
                rows++;
                String[] f = Tsv.split(line, '\t');
                if (f.length != 7) {
                    p.add(file + ": " + f.length + " columns: " + line);
                    continue;
                }
                if (!Shards.of(f[0]).equals(shard)) {
                    p.add(f[0] + " is in " + file + ", but the page looks in " + Shards.file(f[0]));
                }
                String again = NameEntry.fromTsv(f).toTsv(f[0]);
                if (!again.equals(line)) {
                    p.add(file + ": '" + line + "' re-serializes as '" + again + "'");
                }
            }
        }
        p.check();
        assertTrue(rows > 100_000, "entry shards look truncated: " + rows);
    }

    @Test
    void chainStepsHaveALanguage() {
        Problems p = new Problems("chain steps with an empty language");
        for (Map.Entry<String, List<NameEntry>> e : SiteData.entries().entrySet()) {
            for (NameEntry n : e.getValue()) {
                for (OriginStep s : n.chain()) {
                    if (s.language().isBlank()) {
                        p.add(e.getKey() + ": " + n.chain());
                    }
                }
            }
        }
        p.check();
    }

    /** "gem", "ira-old", "ru,uk": a Wiktionary language code (or list) that was never turned into a name. */
    private static final java.util.regex.Pattern RAW_CODE =
            java.util.regex.Pattern.compile("[a-z]{2,3}(-[a-z]{2,4})*(,[a-z]{2,3}(-[a-z]{2,4})*)*");

    @Test
    void languagesAreNamesNotWiktionaryCodes() {
        Problems p = new Problems("chain steps or entries whose language is a raw Wiktionary code");
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Map.Entry<String, List<NameEntry>> e : SiteData.entries().entrySet()) {
            for (NameEntry n : e.getValue()) {
                List<String> langs = new ArrayList<>(List.of(n.language()));
                n.chain().forEach(s -> langs.add(s.language()));
                for (String l : langs) {
                    if (RAW_CODE.matcher(l).matches() || l.equals("unknown")) {
                        counts.merge(l, 1, Integer::sum);
                        p.add(e.getKey() + ": '" + l + "' in " + n.chain());
                    }
                }
            }
        }
        for (String o : SiteData.model().origins()) {
            if (RAW_CODE.matcher(o).matches() || o.equals("unknown")) {
                counts.merge("model origin " + o, 1, Integer::sum);
                p.add("model.tsv origin '" + o + "' (shown to visitors as a guess)");
            }
        }
        p.summary = " " + counts;
        p.check();
    }

    /** Every data file the page downloads. */
    private static List<String> allFiles() {
        List<String> files = new ArrayList<>(List.of("directory.tsv", "model.tsv", "similar.tsv",
                "meaning-tfidf.tsv", "meaning-emb.tsv", "meaning-method.txt"));
        files.addAll(SiteData.shardFiles());
        return files;
    }

    @Test
    void noWiktionaryInlineMarkupLeaksIntoTheData() {
        Problems p = new Problems("leftover Wiktionary inline modifiers");
        for (String file : allFiles()) {
            for (String line : SiteData.lines(file)) {
                for (String marker : List.of("<t:", "<id:", "<alt:", "<tr:", "<g:", "<pos:", "<lit:", "<q:")) {
                    if (line.contains(marker)) {
                        p.add(file + ": " + line);
                        break;
                    }
                }
            }
        }
        p.check();
    }

    @Test
    void noLoneSurrogatesOrReplacementCharacters() {
        // strict decoding in SiteData already rejects malformed UTF-8; this catches text that was cut mid
        // character and written out as U+FFFD, and stray control characters that only render as boxes
        Problems p = new Problems("lines with broken characters");
        for (String file : allFiles()) {
            String text = SiteData.text(file);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean lone = (Character.isHighSurrogate(c) && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))))
                        || (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1))));
                boolean control = c < 0x20 && c != '\t' && c != '\n' && c != '\u001E' && c != '\u001F';
                if (lone || c == '�' || control || c == '\r') {
                    int start = Math.max(0, text.lastIndexOf('\n', i) + 1);
                    int end = text.indexOf('\n', i);
                    p.add(file + " U+" + Integer.toHexString(c) + ": " + text.substring(start, end < 0 ? text.length() : end));
                }
            }
        }
        p.check();
    }

    @Test
    void modelParsesAndEveryIndexIsInRange() {
        OriginModel m = SiteData.model();
        int classes = m.origins().size();
        assertTrue(classes >= 2, "model has " + classes + " origins");
        assertEquals(classes, new HashSet<>(m.origins()).size(), "duplicate origin names: " + m.origins());
        Problems p = new Problems("model lines that are malformed or point past the origin list");
        boolean tau = false;
        boolean floor = false;
        for (String line : SiteData.lines("model.tsv")) {
            String[] f = Tsv.split(line, '\t');
            switch (f[0]) {
                case "C" -> {
                    if (f.length != 4) {
                        p.add(line);
                    }
                }
                case "G" -> {
                    if (f.length != 3) {
                        p.add(line);
                        continue;
                    }
                    for (String pair : Tsv.split(f[2], ',')) {
                        int colon = pair.indexOf(':');
                        int idx = colon < 0 ? -1 : Integer.parseInt(pair.substring(0, colon));
                        if (idx < 0 || idx >= classes) {
                            p.add(line);
                        }
                    }
                }
                case "E" -> {
                    if (f.length != 4) {
                        p.add(line);
                        continue;
                    }
                    int idx = Integer.parseInt(f[2]);
                    if (idx < 0 || idx >= classes || f[3].isBlank()) {
                        p.add(line);
                    }
                }
                case "T" -> tau = Double.parseDouble(f[1]) > 0;
                case "F" -> {
                    double v = Double.parseDouble(f[1]);
                    floor = v > 0 && v < 1;
                }
                default -> p.add("unknown line type: " + line);
            }
        }
        p.check();
        assertTrue(tau, "model has no positive temperature (T line)");
        assertTrue(floor, "model has no confidence floor in (0, 1) (F line)");
        for (String name : List.of("sanjith", "michael", "a", "zzzzzzzz")) {
            List<Scored<String>> r = m.rank(name, classes);
            double sum = r.stream().mapToDouble(Scored::score).sum();
            assertEquals(1.0, sum, 1e-6, name);
            for (int i = 1; i < r.size(); i++) {
                assertTrue(r.get(i - 1).score() >= r.get(i).score(), name + ": " + r);
            }
        }
    }

    @Test
    void tfidfIndexParsesAndPointsOnlyAtKnownNames() {
        Set<String> keys = directoryKeys();
        TfIdfIndex index = TfIdfIndex.parse(SiteData.text("meaning-tfidf.tsv"));
        String[] docs = index.keys();
        assertTrue(docs.length > 1000, "tfidf has " + docs.length + " names");
        assertEquals(docs.length, new HashSet<>(List.of(docs)).size(), "duplicate D lines");
        Problems p = new Problems("tfidf lines that are malformed or point at unknown names");
        for (String d : docs) {
            if (!keys.contains(d)) {
                p.add("D " + d + " is not in directory.tsv");
            }
        }
        for (String line : SiteData.lines("meaning-tfidf.tsv")) {
            String[] f = Tsv.split(line, '\t');
            if (!f[0].equals("P")) {
                continue;
            }
            if (f.length != 4 || !(Tsv.decimal(f[2]) > 0)) {
                p.add(line);
                continue;
            }
            for (String pair : Tsv.split(f[3], ',')) {
                int colon = pair.indexOf(':');
                int doc = Integer.parseInt(pair.substring(0, colon));
                float w = Tsv.decimal(pair.substring(colon + 1));
                if (doc < 0 || doc >= docs.length || !(w > 0) || w > 1.0001f) {
                    p.add(f[1] + ": " + pair);
                }
            }
        }
        p.check();
        assertFalse(index.search("victory", 10).isEmpty(), "'victory' finds nothing");
    }

    @Test
    void embeddingIndexParsesWithFullLengthVectors() {
        Set<String> keys = directoryKeys();
        List<String> lines = SiteData.lines("meaning-emb.tsv");
        String[] head = Tsv.split(lines.get(0), '\t');
        assertEquals("H", head[0]);
        int dim = Integer.parseInt(head[1]);
        Problems p = new Problems("embedding lines that are malformed or point at unknown names");
        Base64.Decoder b64 = Base64.getDecoder();
        Set<String> names = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = Tsv.split(line, '\t');
            String vec = f[0].equals("W") && f.length == 4 ? f[3] : f[0].equals("N") && f.length == 3 ? f[2] : null;
            if (vec == null) {
                p.add(line);
                continue;
            }
            if (b64.decode(vec).length != dim) {
                p.add(f[1] + ": vector of " + b64.decode(vec).length + " bytes, not " + dim);
            }
            if (f[0].equals("N")) {
                if (!keys.contains(f[1])) {
                    p.add("N " + f[1] + " is not in directory.tsv");
                }
                if (!names.add(f[1])) {
                    p.add("N " + f[1] + " twice");
                }
            }
        }
        p.check();
        EmbeddingIndex index = EmbeddingIndex.parse(SiteData.text("meaning-emb.tsv"));
        assertEquals(names.size(), index.keys().length);
        assertFalse(index.search("victory", 10).isEmpty(), "'victory' finds nothing");
    }

    @Test
    void meaningMethodIsOneThePageKnows() {
        assertTrue(Set.of("tfidf", "embedding", "blend").contains(SiteData.text("meaning-method.txt").trim()));
    }

    @Test
    void similarNamesPointOnlyAtKnownNames() {
        Set<String> keys = directoryKeys();
        Problems p = new Problems("similar.tsv rows that are malformed or point at unknown names");
        Set<String> seen = new HashSet<>();
        for (String line : SiteData.lines("similar.tsv")) {
            String[] f = Tsv.split(line, '\t');
            if (f.length != 2 || f[1].isEmpty()) {
                p.add(line);
                continue;
            }
            if (!keys.contains(f[0])) {
                p.add(f[0] + " is not in directory.tsv");
            }
            if (!seen.add(f[0])) {
                p.add(f[0] + " has two rows");
            }
            Set<String> listed = new HashSet<>();
            for (String s : Tsv.split(f[1], ',')) {
                if (!keys.contains(s)) {
                    p.add(f[0] + " lists unknown '" + s + "'");
                }
                if (s.equals(f[0])) {
                    p.add(f[0] + " lists itself");
                }
                if (!listed.add(s)) {
                    p.add(f[0] + " lists '" + s + "' twice");
                }
            }
        }
        p.check();
    }

    @Test
    void variantGroupsStaySmall() {
        // a runaway transitive join (one Wikidata link too many) shows up as one giant group
        Map<Integer, Integer> size = new HashMap<>();
        for (Row r : directoryRows()) {
            size.merge(r.group(), 1, Integer::sum);
        }
        int max = 0;
        int biggest = -1;
        for (Map.Entry<Integer, Integer> e : size.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                biggest = e.getKey();
            }
        }
        List<String> sample = new ArrayList<>();
        for (Row r : directoryRows()) {
            if (r.group() == biggest && sample.size() < 15) {
                sample.add(r.key());
            }
        }
        assertTrue(max < 200, "largest variant group has " + max + " names: " + sample);
    }
}
