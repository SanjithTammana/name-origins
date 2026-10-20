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
 * Loads the data files on demand and caches them. Only the name directory and the origin model load up front;
 * entry details load one first-letter shard at a time, and the meaning data only when a meaning feature is used.
 */
final class Data {
    NameDirectory dir;
    Matcher matcher;
    private String modelText;
    private OriginModel model;
    private boolean modelDone;
    private final Map<String, Map<String, List<NameEntry>>> shards = new HashMap<>();
    private Map<String, String[]> similar;
    private MeaningSearch meaning;

    /** Loads the directory and the origin model in parallel; calls ready when both are in. */
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
    }

    private void finishCore(Runnable ready) {
        if (dir != null && modelDone && matcher == null) {
            matcher = Matcher.standard(dir, this::model);
            ready.run();
        }
    }

    /**
     * Builds the lazy structures (sound keys, origin model, BK-tree) after the page is usable, one per timer tick,
     * so the first misspelled name doesn't pay ~1 s and no single pause blocks typing for long.
     */
    void warmUp() {
        Window.setTimeout(() -> {
            dir.prepareSoundIndex();
            Window.setTimeout(() -> {
                model();
                Window.setTimeout(dir::prepareTree, 50);
            }, 50);
        }, 300);
    }

    private OriginModel model() {
        if (model == null && modelText != null) {
            model = OriginModel.parse(modelText);
            modelText = null;
        }
        return model;
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
