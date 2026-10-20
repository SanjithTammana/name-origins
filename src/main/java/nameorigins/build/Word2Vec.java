package nameorigins.build;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * word2vec, skip-gram with negative sampling, written from scratch (Mikolov et al., 2013).
 *
 * Idea: a word is described by the words around it. For each (center, context) pair seen in the corpus, nudge
 * the center's vector v and the context's output vector u so that sigmoid(v . u) moves towards 1; for a few
 * random "negative" words u', move sigmoid(v . u') towards 0. Words used in similar contexts ("victorious",
 * "conqueror" both near "battle", "win") end up with similar vectors.
 *
 * The update for one (v, u, label) pair, with learning rate a and g = a * (label - sigmoid(v . u)):
 *     u += g * v          (output side)
 *     v += g * u_old      (accumulated over the positive and negative words, applied once)
 *
 * Speed-ups (see PROJECT.md "Complexity"):
 *  - subsampling: frequent words are randomly skipped (keep probability (sqrt(f/t) + 1) * t/f), which cuts the
 *    corpus sharply and improves rare-word vectors;
 *  - negative words come from the unigram distribution^0.75 via the alias method: O(1) per draw, O(V) memory;
 *  - sigmoid comes from a precomputed table;
 *  - threads update the shared arrays without locks ("Hogwild"): collisions are rare and harmless for SGD.
 */
public final class Word2Vec {
    static final int MIN_COUNT = 5;
    static final int MAX_VOCAB = 100_000;
    static final double SAMPLE = 1e-4;
    static final int SIGMOID_TABLE = 1000;
    static final float MAX_EXP = 6f;

    final int dim;
    final int window;
    final int negative;
    final int epochs;
    final float startAlpha;

    String[] words;
    long[] counts;
    float[] syn0;     // input (word) vectors, V x dim
    float[] syn1neg;  // output vectors, V x dim
    private int[] corpus;      // word ids, sentences back to back
    private int[] sentenceEnd; // exclusive end index of each sentence
    private final float[] sigmoid = new float[SIGMOID_TABLE];
    private int[] aliasIndex;
    private double[] aliasProb;
    private float[] keepProb;

    public Word2Vec(int dim, int window, int negative, int epochs, float startAlpha) {
        this.dim = dim;
        this.window = window;
        this.negative = negative;
        this.epochs = epochs;
        this.startAlpha = startAlpha;
        for (int i = 0; i < SIGMOID_TABLE; i++) {
            double x = (i / (double) SIGMOID_TABLE * 2 - 1) * MAX_EXP;
            sigmoid[i] = (float) (1 / (1 + Math.exp(-x)));
        }
    }

    /** Reads the corpus (one tokenized definition per line), keeping at most maxWords words. */
    public void load(Path corpusFile, long maxWords) throws IOException {
        Map<String, Long> freq = new HashMap<>();
        long seen = 0;
        try (BufferedReader in = Files.newBufferedReader(corpusFile)) {
            String line;
            while ((line = in.readLine()) != null && seen < maxWords) {
                for (String w : line.split(" ")) {
                    freq.merge(w, 1L, Long::sum);
                    seen++;
                }
            }
        }
        List<Map.Entry<String, Long>> kept = new ArrayList<>();
        for (Map.Entry<String, Long> e : freq.entrySet()) {
            if (e.getValue() >= MIN_COUNT) {
                kept.add(e);
            }
        }
        kept.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (kept.size() > MAX_VOCAB) {
            kept = kept.subList(0, MAX_VOCAB);
        }
        words = new String[kept.size()];
        counts = new long[kept.size()];
        Map<String, Integer> id = new HashMap<>();
        for (int i = 0; i < words.length; i++) {
            words[i] = kept.get(i).getKey();
            counts[i] = kept.get(i).getValue();
            id.put(words[i], i);
        }

        IntList ids = new IntList();
        IntList ends = new IntList();
        seen = 0;
        try (BufferedReader in = Files.newBufferedReader(corpusFile)) {
            String line;
            while ((line = in.readLine()) != null && seen < maxWords) {
                int before = ids.size;
                for (String w : line.split(" ")) {
                    seen++;
                    Integer i = id.get(w);
                    if (i != null) {
                        ids.add(i);
                    }
                }
                if (ids.size - before >= 2) {
                    ends.add(ids.size);
                } else {
                    ids.size = before;
                }
            }
        }
        corpus = ids.toArray();
        sentenceEnd = ends.toArray();
        buildSampling();
        System.out.printf("  word2vec corpus: %,d words kept of %,d read, vocabulary %,d, %,d sentences%n",
                corpus.length, seen, words.length, sentenceEnd.length);
    }

    private void buildSampling() {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        keepProb = new float[counts.length];
        double[] weight = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            double f = counts[i] / (double) total;
            keepProb[i] = (float) Math.min(1, (Math.sqrt(f / SAMPLE) + 1) * SAMPLE / f);
            weight[i] = Math.pow(counts[i], 0.75);
        }
        buildAlias(weight);
    }

    /**
     * Vose's alias method: split each of V equal-width columns between "itself" and one "alias", so a draw is
     * one uniform column pick plus one coin flip.
     */
    private void buildAlias(double[] weight) {
        int n = weight.length;
        double sum = 0;
        for (double w : weight) {
            sum += w;
        }
        aliasProb = new double[n];
        aliasIndex = new int[n];
        double[] scaled = new double[n];
        int[] small = new int[n];
        int[] large = new int[n];
        int ns = 0;
        int nl = 0;
        for (int i = 0; i < n; i++) {
            scaled[i] = weight[i] * n / sum;
            if (scaled[i] < 1) {
                small[ns++] = i;
            } else {
                large[nl++] = i;
            }
        }
        while (ns > 0 && nl > 0) {
            int s = small[--ns];
            int l = large[--nl];
            aliasProb[s] = scaled[s];
            aliasIndex[s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1;
            if (scaled[l] < 1) {
                small[ns++] = l;
            } else {
                large[nl++] = l;
            }
        }
        while (nl > 0) {
            aliasProb[large[--nl]] = 1;
        }
        while (ns > 0) {
            aliasProb[small[--ns]] = 1;
        }
    }

    int drawNegative(SplittableRandom rnd) {
        int column = rnd.nextInt(aliasProb.length);
        return rnd.nextDouble() < aliasProb[column] ? column : aliasIndex[column];
    }

    /** Trains with the given number of threads; prints progress; saves vectors after every epoch to checkpoint. */
    public void train(int threads, Path checkpoint) throws IOException, InterruptedException {
        int v = words.length;
        syn0 = new float[v * dim];
        syn1neg = new float[v * dim];
        SplittableRandom init = new SplittableRandom(1);
        for (int i = 0; i < syn0.length; i++) {
            syn0[i] = (float) ((init.nextDouble() - 0.5) / dim);
        }
        long totalWork = (long) corpus.length * epochs;
        AtomicLong done = new AtomicLong();
        long started = System.currentTimeMillis();
        for (int epoch = 0; epoch < epochs; epoch++) {
            List<Thread> pool = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int from = (int) ((long) sentenceEnd.length * t / threads);
                int to = (int) ((long) sentenceEnd.length * (t + 1) / threads);
                SplittableRandom rnd = new SplittableRandom(1000L * epoch + t);
                Thread th = new Thread(() -> trainSlice(from, to, rnd, done, totalWork));
                th.setPriority(Thread.MIN_PRIORITY);
                pool.add(th);
                th.start();
            }
            Thread progress = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(15_000);
                        long d = done.get();
                        double secs = (System.currentTimeMillis() - started) / 1000.0;
                        System.out.printf("    %.1f%% of training, %,.0f words/s%n", 100.0 * d / totalWork, d / secs);
                    }
                } catch (InterruptedException ignored) {
                    // training finished
                }
            });
            progress.setDaemon(true);
            progress.start();
            for (Thread th : pool) {
                th.join();
            }
            progress.interrupt();
            save(checkpoint);
            System.out.printf("  epoch %d/%d done in %ds, checkpoint saved%n", epoch + 1, epochs,
                    (System.currentTimeMillis() - started) / 1000);
        }
    }

    private void trainSlice(int fromSentence, int toSentence, SplittableRandom rnd, AtomicLong done, long totalWork) {
        float[] hidden = new float[dim];
        int[] sentence = new int[1024];
        long local = 0;
        for (int s = fromSentence; s < toSentence; s++) {
            int start = s == 0 ? 0 : sentenceEnd[s - 1];
            int end = sentenceEnd[s];
            int len = 0;
            for (int i = start; i < end && len < sentence.length; i++) {
                int w = corpus[i];
                if (keepProb[w] >= 1 || rnd.nextFloat() < keepProb[w]) {
                    sentence[len++] = w;
                }
            }
            local += end - start;
            if (local > 10_000) {
                done.addAndGet(local);
                local = 0;
            }
            float alpha = Math.max(startAlpha * 0.0001f, startAlpha * (1 - done.get() / (float) totalWork));
            for (int pos = 0; pos < len; pos++) {
                int center = sentence[pos];
                int b = 1 + rnd.nextInt(window); // random effective window, as in the original
                for (int c = Math.max(0, pos - b); c < Math.min(len, pos + b + 1); c++) {
                    if (c != pos) {
                        update(center, sentence[c], alpha, rnd, hidden);
                    }
                }
            }
        }
        done.addAndGet(local);
    }

    /** One skip-gram step: the context word's vector predicts the center word plus `negative` noise words. */
    private void update(int target, int context, float alpha, SplittableRandom rnd, float[] grad) {
        int l1 = context * dim;
        java.util.Arrays.fill(grad, 0);
        for (int k = 0; k <= negative; k++) {
            int word;
            int label;
            if (k == 0) {
                word = target;
                label = 1;
            } else {
                word = drawNegative(rnd);
                if (word == target) {
                    continue;
                }
                label = 0;
            }
            int l2 = word * dim;
            float dot = 0;
            for (int d = 0; d < dim; d++) {
                dot += syn0[l1 + d] * syn1neg[l2 + d];
            }
            float g;
            if (dot > MAX_EXP) {
                g = (label - 1) * alpha;
            } else if (dot < -MAX_EXP) {
                g = label * alpha;
            } else {
                int idx = (int) ((dot + MAX_EXP) * (SIGMOID_TABLE / MAX_EXP / 2));
                g = (label - sigmoid[Math.min(SIGMOID_TABLE - 1, idx)]) * alpha;
            }
            for (int d = 0; d < dim; d++) {
                grad[d] += g * syn1neg[l2 + d];
                syn1neg[l2 + d] += g * syn0[l1 + d];
            }
        }
        for (int d = 0; d < dim; d++) {
            syn0[l1 + d] += grad[d];
        }
    }

    public void save(Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(words.length);
            out.writeInt(dim);
            for (int i = 0; i < words.length; i++) {
                out.writeUTF(words[i]);
                out.writeLong(counts[i]);
                for (int d = 0; d < dim; d++) {
                    out.writeFloat(syn0[i * dim + d]);
                }
            }
        }
    }

    /** Loaded vectors: word -> unit-length float vector, plus corpus counts. */
    public record Vectors(Map<String, float[]> vectors, Map<String, Long> counts, int dim) {
        public float cosine(String a, String b) {
            float[] x = vectors.get(a);
            float[] y = vectors.get(b);
            if (x == null || y == null) {
                return Float.NaN;
            }
            float s = 0;
            for (int i = 0; i < x.length; i++) {
                s += x[i] * y[i];
            }
            return s;
        }
    }

    public static Vectors read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            int n = in.readInt();
            int dim = in.readInt();
            Map<String, float[]> vecs = new HashMap<>();
            Map<String, Long> counts = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String w = in.readUTF();
                counts.put(w, in.readLong());
                float[] v = new float[dim];
                double norm = 0;
                for (int d = 0; d < dim; d++) {
                    v[d] = in.readFloat();
                    norm += v[d] * v[d];
                }
                float inv = (float) (1 / Math.sqrt(Math.max(norm, 1e-12)));
                for (int d = 0; d < dim; d++) {
                    v[d] *= inv;
                }
                vecs.put(w, v);
            }
            return new Vectors(vecs, counts, dim);
        }
    }

    /** Growable int array: the corpus is ~30M ints, far too many to box into a List<Integer>. */
    private static final class IntList {
        int[] a = new int[1 << 16];
        int size;

        void add(int v) {
            if (size == a.length) {
                a = java.util.Arrays.copyOf(a, a.length * 2);
            }
            a[size++] = v;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }

    /** args: corpus.txt vectors.bin maxWords threads epochs */
    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args[0]);
        Path out = Path.of(args[1]);
        long maxWords = Long.parseLong(args[2]);
        int threads = Integer.parseInt(args[3]);
        int epochs = Integer.parseInt(args[4]);
        Word2Vec w2v = new Word2Vec(100, 5, 5, epochs, 0.025f);
        w2v.load(corpus, maxWords);
        long t0 = System.currentTimeMillis();
        w2v.train(threads, out);
        System.out.printf("trained in %ds%n", (System.currentTimeMillis() - t0) / 1000);
        Vectors v = read(out);
        for (String[] pair : new String[][] {{"victory", "triumph"}, {"victory", "conquer"}, {"wisdom", "knowledge"},
                {"light", "bright"}, {"god", "divine"}, {"victory", "flower"}}) {
            System.out.printf("  cos(%s, %s) = %.3f%n", pair[0], pair[1], v.cosine(pair[0], pair[1]));
        }
    }
}
