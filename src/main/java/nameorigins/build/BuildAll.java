package nameorigins.build;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rebuilds every generated data file in site/data from the kaikki.org dump, in order:
 *   1. DataPass       raw dump -> work/names.jsonl, langs.tsv, corpus.txt         (~15 min)
 *   2. IndexBuilder   -> site/data/directory.tsv, entries/, work/origins.tsv, meanings.tsv
 *   3. OriginTrainer  -> site/data/model.tsv (+ work/eval-origin.txt)
 *   4. Word2Vec       work/corpus.txt -> work/vectors.bin                        (~4 min, 10 threads)
 *   5. MeaningBuilder -> site/data/meaning-*.tsv, similar.tsv (+ work/eval-meaning.txt)
 * WikidataPull is not part of the default run: the public endpoints time out or rate-limit this query
 * (see WORKLOG). If work/wikidata.tsv exists, IndexBuilder merges it.
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

        System.out.println("1/5 data pass");
        DataPass.run(raw, work);
        System.out.println("2/5 index");
        IndexBuilder.run(work, site);
        System.out.println("3/5 origin model");
        OriginTrainer.run(work, site, work.resolve("eval-origin.txt"));
        System.out.println("4/5 word2vec");
        Word2Vec w2v = new Word2Vec(100, 5, 5, 5, 0.025f);
        w2v.load(work.resolve("corpus.txt"), 30_000_000L);
        w2v.train(threads, work.resolve("vectors.bin"));
        System.out.println("5/5 meaning search");
        MeaningBuilder.run(work, site);
    }
}
