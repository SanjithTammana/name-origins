package nameorigins.core;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Meaning search with word embeddings (trained offline by the project's own word2vec).
 *
 * A text's vector is the idf-weighted average of its words' vectors, scaled to length 1; names were turned into
 * vectors the same way from their meanings. Similarity is the dot product (= cosine for unit vectors), so
 * "victorious" can match a name meaning "conqueror" even though no word is shared.
 *
 * Storage: every vector is one flat byte array (8-bit, value/127), not an object per name. That is 4x smaller
 * than floats and keeps the scan over all names a tight loop over contiguous memory.
 * Query cost O(M * d) for M names and d dimensions; see PROJECT.md for when that would stop being fine.
 *
 * File format (meaning-emb.tsv): "H\td", then "W\tword\tidf\tbase64" per word and "N\tkey\tbase64" per name.
 */
public final class EmbeddingIndex implements MeaningSearch {
    private final int dim;
    private final Map<String, Integer> wordSlot = new HashMap<>();
    private final float[] wordIdf;
    private final byte[] wordVecs;
    private final String[] keys;
    private final byte[] nameVecs;

    private EmbeddingIndex(int dim, int words, int names) {
        this.dim = dim;
        this.wordIdf = new float[words];
        this.wordVecs = new byte[words * dim];
        this.keys = new String[names];
        this.nameVecs = new byte[names * dim];
    }

    public static EmbeddingIndex parse(String tsv) {
        List<String> lines = Tsv.lines(tsv);
        int dim = Integer.parseInt(Tsv.split(lines.get(0), '\t')[1]);
        int words = 0;
        int names = 0;
        for (String l : lines) {
            if (l.startsWith("W\t")) {
                words++;
            } else if (l.startsWith("N\t")) {
                names++;
            }
        }
        EmbeddingIndex x = new EmbeddingIndex(dim, words, names);
        Base64.Decoder b64 = Base64.getDecoder();
        int w = 0;
        int n = 0;
        for (String l : lines) {
            String[] f = Tsv.split(l, '\t');
            if (f[0].equals("W")) {
                x.wordSlot.put(f[1], w);
                x.wordIdf[w] = Tsv.decimal(f[2]);
                System.arraycopy(b64.decode(f[3]), 0, x.wordVecs, w * dim, dim);
                w++;
            } else if (f[0].equals("N")) {
                x.keys[n] = f[1];
                System.arraycopy(b64.decode(f[2]), 0, x.nameVecs, n * dim, dim);
                n++;
            }
        }
        return x;
    }

    /** Unit-length vector for a text, or null when none of its words are known. */
    public float[] embed(String text) {
        float[] v = new float[dim];
        boolean any = false;
        for (String word : Text.tokenize(text)) {
            Integer s = wordSlot.get(word);
            if (s == null) {
                continue;
            }
            any = true;
            float weight = wordIdf[s] / 127f;
            int base = s * dim;
            for (int d = 0; d < dim; d++) {
                v[d] += weight * wordVecs[base + d];
            }
        }
        return any && normalize(v) ? v : null;
    }

    static boolean normalize(float[] v) {
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

    /** Dot product of the query with every name vector: one pass over the flat array. */
    public float[] scores(float[] q) {
        float[] out = new float[keys.length];
        if (q == null) {
            return out;
        }
        for (int i = 0, base = 0; i < keys.length; i++, base += dim) {
            float s = 0;
            for (int d = 0; d < dim; d++) {
                s += q[d] * nameVecs[base + d];
            }
            out[i] = s / 127f;
        }
        return out;
    }

    @Override
    public List<Scored<String>> search(String text, int k) {
        return MeaningSearch.topK(scores(embed(text)), keys, k);
    }

    public String[] keys() {
        return keys;
    }

    public boolean knows(String word) {
        return wordSlot.containsKey(word);
    }
}
