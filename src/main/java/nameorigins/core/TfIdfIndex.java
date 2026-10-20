package nameorigins.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TF-IDF meaning search over an inverted index.
 *
 * Each name's meaning text is a vector: one dimension per word, weight = (times the word appears) x idf,
 * where idf = log((M + 1) / (names containing the word + 1)) + 1, so common words count less. Vectors are scaled
 * to length 1, which makes cosine similarity a plain sum of products over shared words.
 *
 * Inverted index: word -> list of (name, weight). A query only touches the lists of its own words, so its cost
 * is the length of those lists, not the number of names. It can only match words that literally appear;
 * that blind spot is what the embedding index is for.
 *
 * File format (meaning-tfidf.tsv): "D\tkey" per name in index order, then "P\tword\tidf\tdoc:weight,..." per word.
 */
public final class TfIdfIndex implements MeaningSearch {
    private final String[] keys;
    private final Map<String, Float> idf = new HashMap<>();
    private final Map<String, int[]> postingDocs = new HashMap<>();
    private final Map<String, float[]> postingWeights = new HashMap<>();

    private TfIdfIndex(String[] keys) {
        this.keys = keys;
    }

    public static TfIdfIndex parse(String tsv) {
        List<String> lines = Tsv.lines(tsv);
        int docs = 0;
        for (String l : lines) {
            if (l.startsWith("D\t")) {
                docs++;
            }
        }
        String[] keys = new String[docs];
        TfIdfIndex index = new TfIdfIndex(keys);
        int d = 0;
        for (String l : lines) {
            String[] f = Tsv.split(l, '\t');
            if (f[0].equals("D")) {
                keys[d++] = f[1];
            } else if (f[0].equals("P")) {
                String[] pairs = Tsv.split(f[3], ',');
                int[] ids = new int[pairs.length];
                float[] w = new float[pairs.length];
                for (int i = 0; i < pairs.length; i++) {
                    int colon = pairs[i].indexOf(':');
                    ids[i] = Integer.parseInt(pairs[i].substring(0, colon));
                    w[i] = Tsv.decimal(pairs[i].substring(colon + 1));
                }
                index.idf.put(f[1], Tsv.decimal(f[2]));
                index.postingDocs.put(f[1], ids);
                index.postingWeights.put(f[1], w);
            }
        }
        return index;
    }

    @Override
    public List<Scored<String>> search(String text, int k) {
        return MeaningSearch.topK(scores(text), keys, k);
    }

    /** Cosine similarity of the text against every name (0 for names sharing no word). */
    public float[] scores(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String w : Text.tokenize(text)) {
            tf.merge(w, 1, Integer::sum);
        }
        double norm = 0;
        Map<String, Double> q = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Float i = idf.get(e.getKey());
            if (i != null) {
                double w = e.getValue() * i;
                q.put(e.getKey(), w);
                norm += w * w;
            }
        }
        float[] scores = new float[keys.length];
        if (norm == 0) {
            return scores;
        }
        norm = Math.sqrt(norm);
        for (Map.Entry<String, Double> e : q.entrySet()) {
            float qw = (float) (e.getValue() / norm);
            int[] ids = postingDocs.get(e.getKey());
            float[] w = postingWeights.get(e.getKey());
            for (int i = 0; i < ids.length; i++) {
                scores[ids[i]] += qw * w[i];
            }
        }
        return scores;
    }

    public String[] keys() {
        return keys;
    }
}
