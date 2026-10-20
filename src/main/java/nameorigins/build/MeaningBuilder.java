package nameorigins.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import nameorigins.core.BlendSearch;
import nameorigins.core.EmbeddingIndex;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginStep;
import nameorigins.core.Text;
import nameorigins.core.TfIdfIndex;
import nameorigins.core.Tsv;

/**
 * Builds both meaning indexes (TF-IDF and word2vec embeddings), evaluates them on the same tasks, and ships the
 * winner plus a precomputed "similar names" list.
 *
 * Adoption rule, fixed before looking at results: embeddings (or the 50/50 blend) replace TF-IDF only if they
 * raise concept recall@50 AND do not lower etymon recall@10 by more than 0.05. Among those that qualify, the
 * highest concept recall@50 wins. Otherwise TF-IDF ships.
 *
 * Evaluation runs on the parsed index files, i.e. on exactly what the browser loads.
 */
public final class MeaningBuilder {
    static final int EXTRA_VOCAB = 10_000;
    static final int SIMILAR_K = 10;
    static final float SAME_NAME = 0.9f;

    /**
     * Concept queries with hand-written relevance sets. A name counts as relevant to a concept when its meaning
     * contains any word in the set. The query sent is only the first word, so a method that finds "conqueror"
     * names for "victory" is rewarded; one that only matches the literal word is not.
     */
    static final Map<String, List<String>> CONCEPTS = new LinkedHashMap<>();

    static {
        CONCEPTS.put("victory", List.of("victory", "victorious", "conquer", "conqueror", "conquest", "triumph", "triumphant", "win", "winner"));
        CONCEPTS.put("light", List.of("light", "bright", "shining", "shine", "radiant", "ray", "lamp", "dawn", "luminous", "glow"));
        CONCEPTS.put("wisdom", List.of("wisdom", "wise", "knowledge", "learned", "sage", "intelligence", "clever"));
        CONCEPTS.put("peace", List.of("peace", "peaceful", "calm", "tranquil", "serene", "quiet"));
        CONCEPTS.put("strong", List.of("strong", "strength", "mighty", "might", "powerful", "power", "force", "brave"));
        CONCEPTS.put("love", List.of("love", "beloved", "dear", "darling", "loved", "loving", "affection"));
        CONCEPTS.put("king", List.of("king", "ruler", "royal", "rule", "lord", "prince", "reign", "sovereign"));
        CONCEPTS.put("flower", List.of("flower", "blossom", "bloom", "rose", "lily", "petal"));
        CONCEPTS.put("gift", List.of("gift", "bestowed", "present", "granted", "grace"));
        CONCEPTS.put("moon", List.of("moon", "lunar", "crescent"));
        CONCEPTS.put("sea", List.of("sea", "ocean", "marine", "wave", "sailor"));
        CONCEPTS.put("noble", List.of("noble", "nobility", "honorable", "aristocrat", "highborn"));
        CONCEPTS.put("joy", List.of("joy", "happy", "happiness", "joyful", "delight", "glad", "cheerful", "merry"));
        CONCEPTS.put("life", List.of("life", "living", "alive", "live", "vital"));
        CONCEPTS.put("star", List.of("star", "stellar", "astral"));
    }

    private record Doc(String key, String display, List<String> tokens) {}

    public static void run(Path work, Path site) throws IOException {
        List<Doc> docs = loadDocs(work.resolve("meanings.tsv"));
        Map<String, Double> idf = idf(docs);
        TfIdfIndex tfidf = TfIdfIndex.parse(tfidfTsv(docs, idf));
        Word2Vec.Vectors vectors = Word2Vec.read(work.resolve("vectors.bin"));
        String embTsv = embeddingTsv(docs, idf, vectors);
        EmbeddingIndex emb = EmbeddingIndex.parse(embTsv);
        System.out.printf("  meaning docs: %,d (TF-IDF), %,d with an embedding; vocabulary %,d%n",
                tfidf.keys().length, emb.keys().length, idf.size());

        Map<String, Integer> group = groups(site.resolve("data").resolve("directory.tsv"));
        Map<String, Set<String>> etymons = etymons(site.resolve("data").resolve("entries"));

        Map<String, Method> methods = new LinkedHashMap<>();
        methods.put("tfidf", text -> align(tfidf.scores(text), tfidf.keys()));
        methods.put("embedding", text -> align(emb.scores(emb.embed(text)), emb.keys()));
        BlendSearch blend = new BlendSearch(tfidf, emb);
        methods.put("blend", text -> align(blend.scores(text), tfidf.keys()));

        StringBuilder report = new StringBuilder();
        report.append("Adoption rule: a method replaces tfidf only if concept R@50 rises and etymon R@10 drops <= 0.05.\n");
        report.append(String.format(Locale.ROOT, "%-10s %12s %12s %14s %22s%n", "method", "concept P@10", "concept R@50",
                "etymon R@10", "synonym-only R@100 (*)"));
        Map<String, double[]> results = new LinkedHashMap<>();
        Map<String, String> docText = new HashMap<>();
        for (Doc d : docs) {
            docText.put(d.key(), String.join(" ", d.tokens()));
        }
        Map<String, List<String>> byEtymon = byEtymon(docs, etymons);
        List<String> etymonQueries = etymonQueries(docs, etymons, group, byEtymon);
        for (Map.Entry<String, Method> m : methods.entrySet()) {
            double[] concept = conceptScores(m.getValue(), docs);
            double etymon = etymonRecall(m.getValue(), etymonQueries, docText, etymons, group, byEtymon);
            results.put(m.getKey(), new double[] {concept[0], concept[1], etymon});
            report.append(String.format(Locale.ROOT, "%-10s %12.3f %12.3f %14.3f %22.3f%n", m.getKey(), concept[0], concept[1],
                    etymon, concept[2]));
        }
        report.append("(*) diagnostic added after P@10 saturated; not part of the adoption rule.\n");
        report.append(String.format(Locale.ROOT, "etymon queries: %d names that share a source word with another name%n",
                etymonQueries.size()));
        String winner = "tfidf";
        double[] base = results.get("tfidf");
        for (String cand : List.of("embedding", "blend")) {
            double[] r = results.get(cand);
            boolean qualifies = r[1] > base[1] && r[2] >= base[2] - 0.05;
            if (qualifies && r[1] > results.get(winner)[1]) {
                winner = cand;
            }
        }
        report.append("winner: ").append(winner).append('\n');
        report.append(examples(methods.get(winner), docs));

        Path data = site.resolve("data");
        Files.deleteIfExists(data.resolve("meaning-tfidf.tsv"));
        Files.deleteIfExists(data.resolve("meaning-emb.tsv"));
        if (!winner.equals("embedding")) {
            Files.writeString(data.resolve("meaning-tfidf.tsv"), tfidfTsv(docs, idf));
        }
        if (!winner.equals("tfidf")) {
            Files.writeString(data.resolve("meaning-emb.tsv"), embTsv);
        }
        Files.writeString(data.resolve("meaning-method.txt"), winner + "\n");
        writeSimilar(methods.get(winner), docs, docText, group, etymons, data.resolve("similar.tsv"));
        Files.writeString(work.resolve("eval-meaning.txt"), report.toString());
        System.out.print(report);
    }

    /** Scores of one method for one query text, by key. */
    interface Method {
        Map<String, Float> scores(String text);
    }

    private static Map<String, Float> align(float[] scores, String[] keys) {
        Map<String, Float> out = new HashMap<>(keys.length * 2);
        for (int i = 0; i < keys.length; i++) {
            if (scores[i] != 0) {
                out.put(keys[i], scores[i]);
            }
        }
        return out;
    }

    private static List<String> top(Map<String, Float> scores, int k, Set<String> exclude) {
        List<Map.Entry<String, Float>> l = new ArrayList<>();
        for (Map.Entry<String, Float> e : scores.entrySet()) {
            if (e.getValue() > 0 && !exclude.contains(e.getKey())) {
                l.add(e);
            }
        }
        l.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, l.size()); i++) {
            out.add(l.get(i).getKey());
        }
        return out;
    }

    private static List<Doc> loadDocs(Path tsv) throws IOException {
        List<Doc> docs = new ArrayList<>();
        for (String line : Files.readAllLines(tsv)) {
            String[] f = Tsv.split(line, '\t');
            List<String> tokens = Text.tokenize(f[2]);
            if (!tokens.isEmpty()) {
                docs.add(new Doc(f[0], f[1], tokens));
            }
        }
        return docs;
    }

    private static Map<String, Double> idf(List<Doc> docs) {
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            for (String t : new HashSet<>(d.tokens())) {
                df.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((docs.size() + 1.0) / (e.getValue() + 1.0)) + 1);
        }
        return idf;
    }

    private static String tfidfTsv(List<Doc> docs, Map<String, Double> idf) {
        StringBuilder sb = new StringBuilder();
        Map<String, StringBuilder> postings = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Doc d = docs.get(i);
            sb.append("D\t").append(d.key()).append('\n');
            Map<String, Integer> tf = new HashMap<>();
            for (String t : d.tokens()) {
                tf.merge(t, 1, Integer::sum);
            }
            double norm = 0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double w = e.getValue() * idf.get(e.getKey());
                norm += w * w;
            }
            norm = Math.sqrt(norm);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double w = e.getValue() * idf.get(e.getKey()) / norm;
                StringBuilder p = postings.computeIfAbsent(e.getKey(), x -> new StringBuilder());
                p.append(p.length() == 0 ? "" : ",").append(i).append(':').append(String.format(Locale.ROOT, "%.3f", w));
            }
        }
        for (Map.Entry<String, StringBuilder> e : postings.entrySet()) {
            sb.append("P\t").append(e.getKey()).append('\t')
                    .append(String.format(Locale.ROOT, "%.3f", idf.get(e.getKey()))).append('\t').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Ships word vectors for meaning words plus the most frequent corpus words; names as idf-weighted averages. */
    private static String embeddingTsv(List<Doc> docs, Map<String, Double> idf, Word2Vec.Vectors v) {
        int dim = v.dim();
        double maxIdf = Math.log(docs.size() + 1.0) + 1;
        Set<String> ship = new HashSet<>();
        for (String w : idf.keySet()) {
            if (v.vectors().containsKey(w)) {
                ship.add(w);
            }
        }
        List<Map.Entry<String, Long>> byFreq = new ArrayList<>(v.counts().entrySet());
        byFreq.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(EXTRA_VOCAB, byFreq.size()); i++) {
            ship.add(byFreq.get(i).getKey());
        }
        List<String> words = new ArrayList<>(ship);
        Collections.sort(words);
        Base64.Encoder b64 = Base64.getEncoder();
        StringBuilder sb = new StringBuilder("H\t").append(dim).append('\n');
        for (String w : words) {
            sb.append("W\t").append(w).append('\t')
                    .append(String.format(Locale.ROOT, "%.3f", idf.getOrDefault(w, maxIdf))).append('\t')
                    .append(b64.encodeToString(quantize(v.vectors().get(w)))).append('\n');
        }
        for (Doc d : docs) {
            float[] sum = new float[dim];
            boolean any = false;
            for (String t : d.tokens()) {
                float[] wv = v.vectors().get(t);
                if (wv != null) {
                    any = true;
                    double weight = idf.get(t);
                    for (int i = 0; i < dim; i++) {
                        sum[i] += (float) (weight * wv[i]);
                    }
                }
            }
            if (any && unit(sum)) {
                sb.append("N\t").append(d.key()).append('\t').append(b64.encodeToString(quantize(sum))).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean unit(float[] v) {
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        if (n == 0) {
            return false;
        }
        float inv = (float) (1 / Math.sqrt(n));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return true;
    }

    /** Unit vector component in [-1, 1] -> signed byte in [-127, 127]. */
    private static byte[] quantize(float[] unitVector) {
        byte[] out = new byte[unitVector.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Math.round(Math.max(-1, Math.min(1, unitVector[i])) * 127);
        }
        return out;
    }

    private static Map<String, Integer> groups(Path directory) throws IOException {
        Map<String, Integer> g = new HashMap<>();
        for (String line : Files.readAllLines(directory)) {
            String[] f = Tsv.split(line, '\t');
            g.put(f[0], Integer.parseInt(f[2]));
        }
        return g;
    }

    /** key -> its source words ("Ancient Greek|Σοφία"), for the shared-etymon task. */
    private static Map<String, Set<String>> etymons(Path entriesDir) throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        try (var files = Files.list(entriesDir)) {
            for (Path p : files.toList()) {
                for (String line : Files.readAllLines(p)) {
                    String[] f = Tsv.split(line, '\t');
                    NameEntry e = NameEntry.fromTsv(f);
                    for (OriginStep s : e.chain()) {
                        if (!s.form().isEmpty()) {
                            out.computeIfAbsent(f[0], x -> new HashSet<>()).add(s.language() + "|" + s.form());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> sharedEtymon(String key, Map<String, Set<String>> etymons, Map<String, Integer> group,
            Map<String, List<String>> byEtymon) {
        Set<String> rel = new HashSet<>();
        for (String et : etymons.getOrDefault(key, Set.of())) {
            for (String other : byEtymon.getOrDefault(et, List.of())) {
                if (!other.equals(key) && !group.get(other).equals(group.get(key))) {
                    rel.add(other);
                }
            }
        }
        return rel;
    }

    /**
     * source word -> every name with a meaning that has it. Deliberately not filtered to names that have an
     * embedding: a name the embedding method cannot represent must count as its miss, not drop out of the test.
     * (The first version filtered here, which let embedding coverage gaps escape the adoption rule; caught in
     * the Codex review, 2026-10-20.)
     */
    private static Map<String, List<String>> byEtymon(List<Doc> docs, Map<String, Set<String>> etymons) {
        Map<String, List<String>> byEtymon = new HashMap<>();
        for (Doc d : docs) {
            for (String et : etymons.getOrDefault(d.key(), Set.of())) {
                byEtymon.computeIfAbsent(et, x -> new ArrayList<>()).add(d.key());
            }
        }
        return byEtymon;
    }

    /** Up to 1,500 names (fixed seed) that share a source word with a name outside their own variant group. */
    private static List<String> etymonQueries(List<Doc> docs, Map<String, Set<String>> etymons,
            Map<String, Integer> group, Map<String, List<String>> byEtymon) {
        List<String> q = new ArrayList<>();
        for (Doc d : docs) {
            if (!sharedEtymon(d.key(), etymons, group, byEtymon).isEmpty()) {
                q.add(d.key());
            }
        }
        Collections.shuffle(q, new Random(42));
        return q.size() > 1500 ? q.subList(0, 1500) : q;
    }

    private static double etymonRecall(Method m, List<String> queries, Map<String, String> docText,
            Map<String, Set<String>> etymons, Map<String, Integer> group, Map<String, List<String>> byEtymon) {
        double total = 0;
        for (String key : queries) {
            Set<String> rel = sharedEtymon(key, etymons, group, byEtymon);
            Set<String> self = Set.of(key);
            List<String> got = top(m.scores(docText.get(key)), 10, self);
            long hit = got.stream().filter(rel::contains).count();
            total += (double) hit / Math.min(10, rel.size());
        }
        return total / queries.size();
    }

    /**
     * Returns {P@10, R@50, synonym-only R@100}. The last is a diagnostic added after the first run showed P@10
     * saturated at 1.0 for every method: it counts only relevant names whose meaning does NOT contain the query
     * word itself, i.e. the names only a meaning-aware method can find. It is reported, not used by the rule.
     */
    private static double[] conceptScores(Method m, List<Doc> docs) {
        double p10 = 0;
        double r50 = 0;
        double syn = 0;
        int synQueries = 0;
        for (Map.Entry<String, List<String>> c : CONCEPTS.entrySet()) {
            Set<String> words = new HashSet<>();
            for (String w : c.getValue()) {
                words.addAll(Text.tokenize(w));
            }
            Set<String> head = new HashSet<>(Text.tokenize(c.getKey()));
            Set<String> relevant = new HashSet<>();
            Set<String> synonymOnly = new HashSet<>();
            for (Doc d : docs) {
                boolean hit = d.tokens().stream().anyMatch(words::contains);
                if (hit) {
                    relevant.add(d.key());
                    if (d.tokens().stream().noneMatch(head::contains)) {
                        synonymOnly.add(d.key());
                    }
                }
            }
            List<String> got = top(m.scores(c.getKey()), 100, Set.of());
            long hits10 = got.stream().limit(10).filter(relevant::contains).count();
            long hits50 = got.stream().limit(50).filter(relevant::contains).count();
            p10 += hits10 / 10.0;
            r50 += relevant.isEmpty() ? 0 : (double) hits50 / Math.min(50, relevant.size());
            if (!synonymOnly.isEmpty()) {
                syn += (double) got.stream().filter(synonymOnly::contains).count() / Math.min(100, synonymOnly.size());
                synQueries++;
            }
        }
        return new double[] {p10 / CONCEPTS.size(), r50 / CONCEPTS.size(), syn / Math.max(1, synQueries)};
    }

    private static String examples(Method m, List<Doc> docs) {
        Map<String, String> display = new HashMap<>();
        for (Doc d : docs) {
            display.put(d.key(), d.display());
        }
        StringBuilder sb = new StringBuilder("examples with the winner:\n");
        for (String q : List.of("victory", "light", "wisdom", "gift of god", "brave warrior")) {
            List<String> names = new ArrayList<>();
            for (String k : top(m.scores(q), 8, Set.of())) {
                names.add(display.get(k));
            }
            sb.append("  ").append(q).append(": ").append(String.join(", ", names)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Top 10 names by meaning for every name. Leaves out the same name in other languages (Michael -> Mikkjal):
     * its own variant group, names sharing a source word with it, and names with an identical meaning text.
     * All pairs: O(M^2 d), split across threads; run once here so the page only does a lookup.
     */
    private static void writeSimilar(Method m, List<Doc> docs, Map<String, String> docText, Map<String, Integer> group,
            Map<String, Set<String>> etymons, Path out) throws IOException {
        long t0 = System.currentTimeMillis();
        String[] lines = new String[docs.size()];
        IntStream.range(0, docs.size()).parallel().forEach(i -> {
            Doc d = docs.get(i);
            Set<String> exclude = new HashSet<>();
            exclude.add(d.key());
            Set<String> ownEtymons = etymons.getOrDefault(d.key(), Set.of());
            String ownText = docText.get(d.key());
            Map<String, Float> scores = m.scores(ownText);
            // a near-identical meaning statement (score >= SAME_NAME) is the same name in another language,
            // e.g. Michael -> Mikkjal; those are left out so the list shows different names
            scores.entrySet().removeIf(en -> en.getValue() >= SAME_NAME
                    || (group.get(en.getKey()) != null && group.get(en.getKey()).equals(group.get(d.key())))
                    || ownText.equals(docText.get(en.getKey()))
                    || etymons.getOrDefault(en.getKey(), Set.of()).stream().anyMatch(ownEtymons::contains));
            List<String> best = top(scores, SIMILAR_K * 3, exclude);
            // Latin-script names first (stable), so a Latin-script visitor sees names they can read first
            best.sort((a, b) -> Boolean.compare(!Text.isLatinWord(a), !Text.isLatinWord(b)));
            best = best.subList(0, Math.min(SIMILAR_K, best.size()));
            lines[i] = best.isEmpty() ? null : d.key() + "\t" + String.join(",", best);
        });
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l != null) {
                sb.append(l).append('\n');
            }
        }
        Files.writeString(out, sb.toString());
        System.out.printf("  similar names for %,d names in %ds%n", docs.size(), (System.currentTimeMillis() - t0) / 1000);
    }

    public static void main(String[] args) throws IOException {
        run(Path.of(args.length > 0 ? args[0] : "work"), Path.of(args.length > 1 ? args[1] : "site"));
    }
}
