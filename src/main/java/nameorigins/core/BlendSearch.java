package nameorigins.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Half TF-IDF, half embeddings: score = 0.5 * tfidf + 0.5 * max(0, embedding cosine).
 * TF-IDF keeps exact word matches on top; the embedding half adds names that share no word but mean something
 * close. This exact class is used both in the offline evaluation and in the browser.
 */
public final class BlendSearch implements MeaningSearch {
    private final TfIdfIndex tfidf;
    private final EmbeddingIndex emb;
    private final int[] embSlotOfDoc; // tfidf doc index -> embedding index, or -1

    public BlendSearch(TfIdfIndex tfidf, EmbeddingIndex emb) {
        this.tfidf = tfidf;
        this.emb = emb;
        Map<String, Integer> embSlot = new HashMap<>();
        String[] ek = emb.keys();
        for (int i = 0; i < ek.length; i++) {
            embSlot.put(ek[i], i);
        }
        String[] tk = tfidf.keys();
        embSlotOfDoc = new int[tk.length];
        for (int j = 0; j < tk.length; j++) {
            Integer s = embSlot.get(tk[j]);
            embSlotOfDoc[j] = s == null ? -1 : s;
        }
    }

    /** Blended score for every TF-IDF document (the superset of names with a meaning). */
    public float[] scores(String text) {
        float[] f = tfidf.scores(text);
        float[] e = emb.scores(emb.embed(text));
        float[] out = new float[f.length];
        for (int j = 0; j < f.length; j++) {
            int s = embSlotOfDoc[j];
            out[j] = 0.5f * f[j] + (s < 0 ? 0 : 0.5f * Math.max(0, e[s]));
        }
        return out;
    }

    @Override
    public List<Scored<String>> search(String text, int k) {
        return MeaningSearch.topK(scores(text), tfidf.keys(), k);
    }
}
