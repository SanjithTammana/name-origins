package nameorigins.build;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rebuilds every generated data file in site/data from the kaikki.org dump, in order:
 *   1. DataPass       raw dump -> work/names.jsonl, langs.tsv, corpus.txt         (~15 min)
 *   2. WikidataPull   -> work/wikidata.tsv (given names, language, gender, same-as) (~5 min, network)
 *   3. WikipediaPull + WikipediaNames -> cached full articles, work/wikipedia.tsv
 *   4. IndexBuilder   -> site/data/directory.tsv, entries/, work/origins.tsv, meanings.tsv
 *   5. OriginTrainer  -> site/data/model.tsv, usage-model.tsv (+ work/eval-origin.txt)
 *   6. Word2Vec       work/corpus.txt -> work/vectors.bin                        (~4 min, 10 threads)
 *   7. MeaningBuilder -> site/data/meaning-*.tsv, similar.tsv (+ work/eval-meaning.txt)
 * If the Wikidata endpoint is down, step 2 is skipped with a warning and an existing work/wikidata.tsv is used.
 *
 * Usage: mvnw compile exec:java -Dexec.args="path/to/raw-wiktextract-data.jsonl.gz"
 */
public final class BuildAll {
    private BuildAll() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: BuildAll <raw-wiktextract-data.jsonl.gz> [work dir] [site dir]");
            System.exit(2);
        }
        Path raw = Path.of(args[0]);
        Path work = Path.of(args.length > 1 ? args[1] : "work");
        Path site = Path.of(args.length > 2 ? args[2] : "site");
        Files.createDirectories(site.resolve("data"));
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

        System.out.println("1/7 data pass");
        DataPass.run(raw, work);
        System.out.println("2/7 wikidata");
        try {
            WikidataPull.run(work, WikidataPull.WIKIDATA_ENDPOINT);
        } catch (java.io.IOException e) {
            System.out.println("  WARNING: Wikidata pull failed (" + e.getMessage() + "); using any existing work/wikidata.tsv");
        }
        System.out.println("3/7 wikipedia");
        try {
            WikipediaPull.run(work);
            WikipediaNames.run(work);
        } catch (java.io.IOException e) {
            if (!Files.exists(work.resolve("wikipedia.tsv"))) throw e;
            System.out.println("  WARNING: Wikipedia refresh failed (" + e.getMessage()
                    + "); using cached work/wikipedia.tsv");
        }
        System.out.println("4/7 index");
        IndexBuilder.run(work, site);
        System.out.println("5/7 origin and usage models");
        OriginTrainer.run(work, site, work.resolve("eval-origin.txt"));
        System.out.println("6/7 word2vec");
        Word2Vec w2v = new Word2Vec(100, 5, 5, 5, 0.025f);
        w2v.load(work.resolve("corpus.txt"), 30_000_000L);
        w2v.train(threads, work.resolve("vectors.bin"));
        System.out.println("7/7 meaning search");
        MeaningBuilder.run(work, site);
    }
}
