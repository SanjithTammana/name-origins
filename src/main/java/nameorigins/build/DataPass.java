package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import nameorigins.core.Text;

/**
 * One streaming pass over the raw kaikki.org dump (raw-wiktextract-data.jsonl.gz, ~2.9 GB compressed).
 * Reads one line at a time, so memory stays flat regardless of file size. Produces, in the work directory:
 *   names.jsonl  - given-name entries, verbatim (NameFilter)
 *   langs.tsv    - language code -> language name, by majority vote over every "lang"/"lang_code" pair seen
 *   corpus.txt   - tokenized English definitions for word2vec, one definition per line, capped at CORPUS_CAP words
 */
public final class DataPass {
    private DataPass() {}

    static final long CORPUS_CAP = 30_000_000L;
    /** Take every third entry's definitions so the corpus spans the whole dump rather than its first part. */
    static final int CORPUS_EVERY = 3;

    private static final Pattern LANG_PAIR =
            Pattern.compile("\"lang\": \"([^\"]+)\", \"lang_code\": \"([^\"]+)\"");

    public static void run(Path rawGz, Path work) throws IOException {
        Files.createDirectories(work);
        ObjectMapper json = new ObjectMapper();
        Map<String, Map<String, Integer>> langVotes = new HashMap<>();
        long lines = 0;
        long kept = 0;
        long corpusWords = 0;
        long started = System.currentTimeMillis();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(rawGz), 1 << 16), StandardCharsets.UTF_8), 1 << 16);
                BufferedWriter names = Files.newBufferedWriter(work.resolve("names.jsonl"));
                BufferedWriter corpus = Files.newBufferedWriter(work.resolve("corpus.txt"))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines++;
                Matcher m = LANG_PAIR.matcher(line);
                while (m.find()) {
                    langVotes.computeIfAbsent(m.group(2), k -> new HashMap<>()).merge(m.group(1), 1, Integer::sum);
                }
                boolean maybeName = line.contains("\"pos\": \"name\"");
                boolean wantCorpus = corpusWords < CORPUS_CAP && lines % CORPUS_EVERY == 0;
                if (!maybeName && !wantCorpus) {
                    continue;
                }
                JsonNode entry = json.readTree(line);
                if (maybeName && NameFilter.checksNameEntry(entry)) {
                    names.write(line);
                    names.newLine();
                    kept++;
                }
                if (wantCorpus) {
                    corpusWords += writeDefinitions(entry, corpus);
                }
                if (lines % 500_000 == 0) {
                    System.out.printf("  %,d lines, %,d names kept, %,d corpus words, %ds%n",
                            lines, kept, corpusWords, (System.currentTimeMillis() - started) / 1000);
                }
            }
        }

        try (BufferedWriter out = Files.newBufferedWriter(work.resolve("langs.tsv"))) {
            for (Map.Entry<String, Map<String, Integer>> e : langVotes.entrySet()) {
                Map.Entry<String, Integer> best = null;
                for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                    if (best == null || v.getValue() > best.getValue()) {
                        best = v;
                    }
                }
                out.write(e.getKey() + "\t" + best.getKey() + "\t" + best.getValue());
                out.newLine();
            }
        }
        System.out.printf("done: %,d lines, %,d names, %,d languages, %,d corpus words, %ds%n",
                lines, kept, langVotes.size(), corpusWords, (System.currentTimeMillis() - started) / 1000);
    }

    private static long writeDefinitions(JsonNode entry, BufferedWriter corpus) throws IOException {
        long words = 0;
        for (JsonNode sense : entry.path("senses")) {
            for (JsonNode gloss : sense.path("glosses")) {
                List<String> tokens = Text.tokenize(gloss.asText());
                if (tokens.size() < 2) {
                    continue; // one-word definitions carry no co-occurrence signal
                }
                corpus.write(String.join(" ", tokens));
                corpus.newLine();
                words += tokens.size();
            }
        }
        return words;
    }

    public static void main(String[] args) throws IOException {
        run(Path.of(args[0]), Path.of(args.length > 1 ? args[1] : "work"));
    }
}
