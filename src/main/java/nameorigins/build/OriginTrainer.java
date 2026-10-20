package nameorigins.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nameorigins.core.OriginModel;
import nameorigins.core.Scored;
import nameorigins.core.Text;
import nameorigins.core.Tsv;

/**
 * Trains, tunes and evaluates the origin guesser (OriginModel), then writes site/data/model.tsv.
 *
 * Split by a hash of the name: 20% test (never seen while tuning), 10% validation (for choosing settings),
 * 70% train. Every number reported comes from the test part. The shipped model is then retrained on all names
 * with the chosen settings.
 *
 * Evaluation goes through OriginModel.parse, the exact code the browser runs, so the reported accuracy is the
 * accuracy of what ships.
 */
public final class OriginTrainer {
    static final int MIN_CLASS = 40;
    static final double TARGET_PRECISION = 0.6;
    static final double[] ALPHAS = {0.05, 0.1, 0.25, 0.5, 1.0};
    static final double[] PRIOR_POWERS = {0.0, 0.5, 1.0};
    static final double[] TAUS = {1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24};

    record Example(String key, String display, String origin) {}

    public static void run(Path work, Path site, Path report) throws IOException {
        List<Example> all = load(work.resolve("origins.tsv"));
        List<Example> train = new ArrayList<>();
        List<Example> val = new ArrayList<>();
        List<Example> test = new ArrayList<>();
        for (Example e : all) {
            int bucket = (e.key().hashCode() & 0x7fffffff) % 10;
            (bucket < 2 ? test : bucket == 2 ? val : train).add(e);
        }
        StringBuilder out = new StringBuilder();
        Map<String, Integer> classSizes = counts(all);
        out.append(String.format(Locale.ROOT, "names: %,d  origins kept: %d  train/val/test: %,d/%,d/%,d%n",
                all.size(), classSizes.size(), train.size(), val.size(), test.size()));

        double bestAcc = -1;
        double bestAlpha = 0;
        double bestPower = 0;
        for (double a : ALPHAS) {
            for (double p : PRIOR_POWERS) {
                double acc = accuracy(OriginModel.parse(modelTsv(train, a, p, 1, 0.5)), val, 1);
                if (acc > bestAcc) {
                    bestAcc = acc;
                    bestAlpha = a;
                    bestPower = p;
                }
            }
        }
        double bestTau = 1;
        double bestNll = Double.MAX_VALUE;
        for (double t : TAUS) {
            double nll = nll(OriginModel.parse(modelTsv(train, bestAlpha, bestPower, t, 0.5)), val);
            if (nll < bestNll) {
                bestNll = nll;
                bestTau = t;
            }
        }
        OriginModel tuned = OriginModel.parse(modelTsv(train, bestAlpha, bestPower, bestTau, 0.5));
        double floor = chooseFloor(tuned, val);
        out.append(String.format(Locale.ROOT, "settings chosen on validation: alpha=%.2f prior^%.1f tau=%.1f floor=%.2f%n",
                bestAlpha, bestPower, bestTau, floor));

        String majority = classSizes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        long majorityHits = test.stream().filter(e -> e.origin().equals(majority)).count();
        out.append(String.format(Locale.ROOT, "TEST top-1 accuracy: %.3f   top-3: %.3f   baseline (always %s): %.3f%n",
                accuracy(tuned, test, 1), accuracy(tuned, test, 3), majority, (double) majorityHits / test.size()));
        int shown = 0;
        int correct = 0;
        for (Example e : test) {
            Scored<String> top = tuned.rank(e.key(), 1).get(0);
            if (top.score() >= floor) {
                shown++;
                if (top.item().equals(e.origin())) {
                    correct++;
                }
            }
        }
        out.append(String.format(Locale.ROOT,
                "TEST above the confidence floor: shown for %.1f%% of names, correct %.1f%% of the time%n",
                100.0 * shown / test.size(), 100.0 * correct / Math.max(1, shown)));
        out.append(perClass(tuned, test));

        String shipped = modelTsv(all, bestAlpha, bestPower, bestTau, floor);
        Files.writeString(site.resolve("data").resolve("model.tsv"), shipped);
        out.append(String.format(Locale.ROOT, "shipped model.tsv: %,d bytes%n", shipped.length()));
        Files.writeString(report, out.toString());
        System.out.print(out);
    }

    static List<Example> load(Path tsv) throws IOException {
        List<Example> raw = new ArrayList<>();
        for (String line : Files.readAllLines(tsv)) {
            String[] f = Tsv.split(line, '\t');
            if (f.length == 3 && Text.isLatinWord(f[0]) && f[0].length() >= 2 && f[0].chars().allMatch(Character::isLetter)) {
                raw.add(new Example(f[0], f[1], OriginGroups.of(f[2])));
            }
        }
        Map<String, Integer> sizes = counts(raw);
        List<Example> kept = new ArrayList<>();
        for (Example e : raw) {
            if (sizes.get(e.origin()) >= MIN_CLASS) {
                kept.add(e);
            }
        }
        return kept;
    }

    private static Map<String, Integer> counts(List<Example> examples) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Example e : examples) {
            m.merge(e.origin(), 1, Integer::sum);
        }
        return m;
    }

    /** Writes the sparse model in the format OriginModel.parse reads. */
    static String modelTsv(List<Example> data, double alpha, double priorPower, double tau, double floor) {
        List<String> classes = new ArrayList<>(counts(data).keySet());
        Map<String, Integer> ci = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            ci.put(classes.get(i), i);
        }
        int c = classes.size();
        Map<String, int[]> gramCounts = new HashMap<>();
        int[] namesPerClass = new int[c];
        for (Example e : data) {
            int k = ci.get(e.origin());
            namesPerClass[k]++;
            for (String g : OriginModel.ngrams(e.key())) {
                gramCounts.computeIfAbsent(g, x -> new int[c])[k]++;
            }
        }
        // prune n-grams seen only once overall: they are treated as unseen (default probability)
        gramCounts.values().removeIf(arr -> sum(arr) < 2);
        long[] gramsPerClass = new long[c];
        for (int[] arr : gramCounts.values()) {
            for (int k = 0; k < c; k++) {
                gramsPerClass[k] += arr[k];
            }
        }
        int vocab = gramCounts.size();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < c; k++) {
            double prior = priorPower * Math.log((double) namesPerClass[k] / data.size());
            double dflt = Math.log(alpha / (gramsPerClass[k] + alpha * vocab));
            sb.append("C\t").append(classes.get(k)).append('\t').append(fmt(prior)).append('\t').append(fmt(dflt)).append('\n');
        }
        for (Map.Entry<String, int[]> e : gramCounts.entrySet()) {
            sb.append("G\t").append(e.getKey()).append('\t');
            boolean first = true;
            for (int k = 0; k < c; k++) {
                int n = e.getValue()[k];
                if (n > 0) {
                    sb.append(first ? "" : ",").append(k).append(':').append(fmt(Math.log(1 + n / alpha)));
                    first = false;
                }
            }
            sb.append('\n');
        }
        appendEvidence(sb, data, gramCounts, ci);
        sb.append("T\t").append(tau).append('\n');
        sb.append("F\t").append(floor).append('\n');
        return sb.toString();
    }

    /**
     * Example names for distinctive pieces: a 3- or 4-letter piece seen 3+ times, mostly (>= 60%) under one
     * origin. These are what the page shows as "names ending in -jit: ...".
     */
    private static void appendEvidence(StringBuilder sb, List<Example> data, Map<String, int[]> gramCounts,
            Map<String, Integer> ci) {
        Map<String, Integer> distinctive = new HashMap<>();
        for (Map.Entry<String, int[]> e : gramCounts.entrySet()) {
            if (e.getKey().length() < 3) {
                continue;
            }
            int[] arr = e.getValue();
            int total = sum(arr);
            for (int k = 0; k < arr.length; k++) {
                if (arr[k] >= 3 && arr[k] >= TARGET_PRECISION * total) {
                    distinctive.put(e.getKey(), k);
                }
            }
        }
        Map<String, List<String>> examples = new HashMap<>();
        List<Example> shortestFirst = new ArrayList<>(data);
        shortestFirst.sort((a, b) -> a.key().length() - b.key().length());
        for (Example e : shortestFirst) {
            int k = ci.get(e.origin());
            for (String g : OriginModel.ngrams(e.key())) {
                Integer want = distinctive.get(g);
                if (want != null && want == k) {
                    List<String> l = examples.computeIfAbsent(g, x -> new ArrayList<>());
                    if (l.size() < 3 && !l.contains(e.display())) {
                        l.add(e.display());
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> e : examples.entrySet()) {
            sb.append("E\t").append(e.getKey()).append('\t').append(distinctive.get(e.getKey())).append('\t')
                    .append(Tsv.clean(String.join(", ", e.getValue()))).append('\n');
        }
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static double accuracy(OriginModel m, List<Example> data, int k) {
        int hit = 0;
        for (Example e : data) {
            for (Scored<String> s : m.rank(e.key(), k)) {
                if (s.item().equals(e.origin())) {
                    hit++;
                    break;
                }
            }
        }
        return (double) hit / data.size();
    }

    private static double nll(OriginModel m, List<Example> data) {
        double total = 0;
        int n = m.origins().size();
        for (Example e : data) {
            double p = 1e-9;
            for (Scored<String> s : m.rank(e.key(), n)) {
                if (s.item().equals(e.origin())) {
                    p = Math.max(p, s.score());
                }
            }
            total -= Math.log(p);
        }
        return total / data.size();
    }

    /** Lowest floor whose validation precision reaches the target, so the guess is shown as often as is honest. */
    private static double chooseFloor(OriginModel m, List<Example> val) {
        for (double t = 0.20; t <= 0.95; t += 0.05) {
            int shown = 0;
            int correct = 0;
            for (Example e : val) {
                Scored<String> top = m.rank(e.key(), 1).get(0);
                if (top.score() >= t) {
                    shown++;
                    if (top.item().equals(e.origin())) {
                        correct++;
                    }
                }
            }
            if (shown >= 20 && (double) correct / shown >= TARGET_PRECISION) {
                return Math.round(t * 100) / 100.0;
            }
        }
        return 0.95;
    }

    private static String perClass(OriginModel m, List<Example> test) {
        Map<String, int[]> pc = new LinkedHashMap<>();
        for (Example e : test) {
            int[] a = pc.computeIfAbsent(e.origin(), x -> new int[2]);
            a[0]++;
            if (m.rank(e.key(), 1).get(0).item().equals(e.origin())) {
                a[1]++;
            }
        }
        StringBuilder sb = new StringBuilder("TEST top-1 by origin (largest 12):\n");
        pc.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).limit(12).forEach(e ->
                sb.append(String.format(Locale.ROOT, "  %-14s n=%4d  %.2f%n", e.getKey(), e.getValue()[0],
                        (double) e.getValue()[1] / e.getValue()[0])));
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        Path work = Path.of(args.length > 0 ? args[0] : "work");
        run(work, Path.of(args.length > 1 ? args[1] : "site"), work.resolve("eval-origin.txt"));
    }
}
