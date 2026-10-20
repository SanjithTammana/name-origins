package nameorigins.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Character n-gram naive Bayes: guesses where a name comes from by its letter patterns.
 *
 * A name is padded as "^sanjith$" and cut into its 2-, 3- and 4-letter pieces ("^s", "ith", "th$", ...).
 * For each origin c:  score(c) = log P(c) + sum over pieces g of log P(g | c),
 * with P(g | c) = (count(g, c) + alpha) / (total(c) + alpha * V)  (add-alpha smoothing, so an unseen piece
 * lowers a score instead of zeroing it). Sums of logs instead of products of probabilities avoid underflow.
 *
 * Stored sparse: most pieces occur under only a few origins, so only seen (piece, origin) pairs are kept.
 * For a seen pair, log P(g|c) = default(c) + log(1 + count/alpha), so the file stores that bonus per pair and
 * one default per origin. Scoring is then: prior + pieces * default + the bonuses of the pairs that are present.
 *
 * Naive Bayes is overconfident (it treats overlapping pieces as independent), so scores are divided by a
 * temperature tau, fitted offline on held-out names, before turning them into probabilities.
 */
public final class OriginModel {
    private final List<String> origins = new ArrayList<>();
    private final List<Double> logPrior = new ArrayList<>();
    private final List<Double> logDefault = new ArrayList<>();
    private final Map<String, int[]> pairOrigins = new HashMap<>();
    private final Map<String, float[]> pairBonus = new HashMap<>();
    private final Map<String, String> examples = new HashMap<>();
    private double tau = 1;
    private double floor = 0.5;

    public static final int MIN_N = 2;
    public static final int MAX_N = 4;

    /** Parses model.tsv (format written by the offline trainer, see OriginTrainer). */
    public static OriginModel parse(String tsv) {
        OriginModel m = new OriginModel();
        for (String line : Tsv.lines(tsv)) {
            String[] f = Tsv.split(line, '\t');
            switch (f[0]) {
                case "C" -> {
                    m.origins.add(f[1]);
                    m.logPrior.add(Double.parseDouble(f[2]));
                    m.logDefault.add(Double.parseDouble(f[3]));
                }
                case "G" -> {
                    String[] pairs = Tsv.split(f[2], ',');
                    int[] idx = new int[pairs.length];
                    float[] bonus = new float[pairs.length];
                    for (int i = 0; i < pairs.length; i++) {
                        int colon = pairs[i].indexOf(':');
                        idx[i] = Integer.parseInt(pairs[i].substring(0, colon));
                        bonus[i] = Tsv.decimal(pairs[i].substring(colon + 1));
                    }
                    m.pairOrigins.put(f[1], idx);
                    m.pairBonus.put(f[1], bonus);
                }
                case "E" -> m.examples.put(f[1] + "|" + f[2], f[3]);
                case "T" -> m.tau = Double.parseDouble(f[1]);
                case "F" -> m.floor = Double.parseDouble(f[1]);
                default -> { }
            }
        }
        return m;
    }

    /** The pieces of a folded name, e.g. "ana" -> ^a, an, na, a$, ^an, ana, na$, ^ana, ana$. */
    public static List<String> ngrams(String folded) {
        String s = "^" + folded + "$";
        List<String> out = new ArrayList<>();
        for (int n = MIN_N; n <= MAX_N; n++) {
            for (int i = 0; i + n <= s.length(); i++) {
                out.add(s.substring(i, i + n));
            }
        }
        return out;
    }

    /** Raw log scores, one per origin (index order of {@link #origins()}). */
    public double[] scores(String folded) {
        List<String> grams = ngrams(folded);
        int c = origins.size();
        double[] score = new double[c];
        for (int i = 0; i < c; i++) {
            score[i] = logPrior.get(i) + grams.size() * logDefault.get(i);
        }
        for (String g : grams) {
            int[] idx = pairOrigins.get(g);
            if (idx != null) {
                float[] bonus = pairBonus.get(g);
                for (int k = 0; k < idx.length; k++) {
                    score[idx[k]] += bonus[k];
                }
            }
        }
        return score;
    }

    /** Top k origins with probabilities (softmax of score / tau), best first. One pass, no full sort. */
    public List<Scored<String>> rank(String folded, int k) {
        double[] s = scores(folded);
        double max = Double.NEGATIVE_INFINITY;
        for (double v : s) {
            max = Math.max(max, v);
        }
        double sum = 0;
        double[] p = new double[s.length];
        for (int i = 0; i < s.length; i++) {
            p[i] = Math.exp((s[i] - max) / tau);
            sum += p[i];
        }
        List<Scored<String>> top = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            Scored<String> item = new Scored<>(origins.get(i), p[i] / sum);
            int at = top.size();
            while (at > 0 && top.get(at - 1).score() < item.score()) {
                at--;
            }
            if (at < k) {
                top.add(at, item);
                if (top.size() > k) {
                    top.remove(k);
                }
            }
        }
        return top;
    }

    /** Lines like "-jit: Abhijit, Indrajit" for the query's pieces most typical of the origin. */
    public List<String> evidence(String folded, String origin, int k) {
        int oi = origins.indexOf(origin);
        List<String> out = new ArrayList<>();
        List<String> grams = ngrams(folded);
        // longest pieces first ("jit$" says more than "it"), and at each length the ones anchored to the start
        // or end of the name first ("-jit" says more than "-jit-"); an example list is shown once
        List<String> shownExamples = new ArrayList<>();
        for (int n = MAX_N; n >= MIN_N && out.size() < k; n--) {
            for (int pass = 0; pass < 2; pass++) {
                for (String g : grams) {
                    boolean anchored = g.startsWith("^") || g.endsWith("$");
                    if (g.length() != n || anchored != (pass == 0) || out.size() >= k) {
                        continue;
                    }
                    String ex = examples.get(g + "|" + oi);
                    if (ex != null && !shownExamples.contains(ex)) {
                        shownExamples.add(ex);
                        out.add(pretty(g) + ": " + ex);
                    }
                }
            }
        }
        return out;
    }

    private static String pretty(String g) {
        boolean start = g.startsWith("^");
        boolean end = g.endsWith("$");
        String core = g.substring(start ? 1 : 0, g.length() - (end ? 1 : 0));
        return (start ? "" : "-") + core + (end ? "" : "-");
    }

    public List<String> origins() {
        return origins;
    }

    public double confidenceFloor() {
        return floor;
    }
}
