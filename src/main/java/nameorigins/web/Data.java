package nameorigins.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import nameorigins.core.BlendSearch;
import nameorigins.core.EmbeddingIndex;
import nameorigins.core.Matcher;
import nameorigins.core.MeaningSearch;
import nameorigins.core.NameDirectory;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginModel;
import nameorigins.core.Shards;
import nameorigins.core.TfIdfIndex;
import nameorigins.core.Tsv;
import org.teavm.jso.browser.Window;

/**
 * Loads the data files on demand and caches them. Only the name directory and the two small model files load up front;
 * entry details load one first-letter shard at a time, and the meaning data only when a meaning feature is used.
 */
final class Data {
    NameDirectory dir;
    Matcher matcher;
    private String modelText;
    private OriginModel model;
    private boolean modelDone;
    private String usageText;
    private OriginModel usageModel;
    private boolean usageDone;
    private final Map<String, Map<String, List<NameEntry>>> shards = new HashMap<>();
    private Map<String, String[]> similar;
    private MeaningSearch meaning;

    /** Loads the directory and model files in parallel; calls ready when all have answered. */
    void loadCore(Runnable ready, Consumer<String> failed) {
        Http.get("data/directory.tsv", text -> {
            dir = NameDirectory.parse(text);
            finishCore(ready);
        }, failed);
        Http.get("data/model.tsv", text -> {
            modelText = text; // parsed on first use: most lookups never need a guess
            modelDone = true;
            finishCore(ready);
        }, err -> {
            modelDone = true; // the page still works without the guesser
            finishCore(ready);
        });
        Http.get("data/usage-model.tsv", text -> {
            usageText = text;
            usageDone = true;
            finishCore(ready);
        }, err -> {
            usageDone = true; // the original matcher still works without this optional model
            finishCore(ready);
        });
    }

    private void finishCore(Runnable ready) {
        if (dir != null && modelDone && usageDone && matcher == null) {
            matcher = Matcher.standard(dir, this::model, this::usageModel);
            ready.run();
        }
    }

    /**
     * Builds the lazy structures (sound keys, origin model, BK-tree) after the page is usable, in slices across
     * timer ticks, so the first misspelled name doesn't pay for them and no single pause blocks typing for long.
     */
    void warmUp() {
        Window.setTimeout(() -> slices(() -> dir.buildSoundIndexStep(SOUND_SLICE), () -> {
            model();
            usageModel();
            Window.setTimeout(() -> slices(() -> dir.buildTreeStep(TREE_SLICE), () -> { }), 0);
        }), 300);
    }

    /** Rows per tick; measured: the longest slice took 177 ms on a desktop (150k names). */
    private static final int SOUND_SLICE = 15_000;
    private static final int TREE_SLICE = 20_000;

    /** Runs step until it returns true, one call per timer tick, then runs done. */
    private static void slices(java.util.function.BooleanSupplier step, Runnable done) {
        if (step.getAsBoolean()) {
            done.run();
        } else {
            Window.setTimeout(() -> slices(step, done), 0);
        }
    }

    private OriginModel model() {
        if (model == null && modelText != null) {
            model = OriginModel.parse(modelText);
            modelText = null;
        }
        return model;
    }

    private OriginModel usageModel() {
        if (usageModel == null && usageText != null) {
            usageModel = OriginModel.parse(usageText);
            usageText = null;
        }
        return usageModel;
    }

    void entries(String key, Consumer<List<NameEntry>> done) {
        String shard = Shards.of(key);
        Map<String, List<NameEntry>> loaded = shards.get(shard);
        if (loaded != null) {
            done.accept(loaded.getOrDefault(key, List.of()));
            return;
        }
        Http.get(Shards.file(key), text -> {
            Map<String, List<NameEntry>> m = new HashMap<>();
            for (String line : Tsv.lines(text)) {
                String[] f = Tsv.split(line, '\t');
                m.computeIfAbsent(f[0], k -> new ArrayList<>()).add(NameEntry.fromTsv(f));
            }
            shards.put(shard, m);
            done.accept(m.getOrDefault(key, List.of()));
        }, err -> done.accept(List.of()));
    }

    /** Display names of the names with the closest meaning (precomputed offline). */
    void similar(String key, Consumer<List<String>> done) {
        if (similar != null) {
            done.accept(displays(similar.get(key)));
            return;
        }
        Http.get("data/similar.tsv", text -> {
            similar = new HashMap<>();
            for (String line : Tsv.lines(text)) {
                String[] f = Tsv.split(line, '\t');
                similar.put(f[0], Tsv.split(f[1], ','));
            }
            done.accept(displays(similar.get(key)));
        }, err -> done.accept(List.of()));
    }

    List<String> displays(String[] keys) {
        List<String> out = new ArrayList<>();
        if (keys == null) {
            return out;
        }
        for (String k : keys) {
            int slot = dir.slot(k);
            out.add(slot >= 0 ? dir.display(slot) : k);
        }
        return out;
    }

    /** Loads whichever meaning index the build chose (meaning-method.txt: tfidf, embedding or blend). */
    void meaning(Consumer<MeaningSearch> done, Consumer<String> failed) {
        if (meaning != null) {
            done.accept(meaning);
            return;
        }
        Http.get("data/meaning-method.txt", method -> {
            String m = method.trim();
            if (m.equals("tfidf")) {
                Http.get("data/meaning-tfidf.tsv", t -> done.accept(meaning = TfIdfIndex.parse(t)), failed);
            } else if (m.equals("embedding")) {
                Http.get("data/meaning-emb.tsv", t -> done.accept(meaning = EmbeddingIndex.parse(t)), failed);
            } else {
                Http.get("data/meaning-tfidf.tsv", t -> {
                    TfIdfIndex tfidf = TfIdfIndex.parse(t);
                    Http.get("data/meaning-emb.tsv",
                            e -> done.accept(meaning = new BlendSearch(tfidf, EmbeddingIndex.parse(e))), failed);
                }, failed);
            }
        }, failed);
    }
}
