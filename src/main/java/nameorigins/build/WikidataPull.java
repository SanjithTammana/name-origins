package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls given names from Wikidata (CC0) into work/wikidata.tsv:
 *   label \t language-of-name label \t "said to be the same as" label \t gender (male/female/unisex/"")
 *
 * Two phases, because one query that joins every name to its label times out on the public endpoint (it 504'd
 * from the letter H on, even split by class and first letter):
 *   1. per name class, only item IDs plus their P407 (language) and P460 (same as) IDs: no label joins;
 *   2. labels for every ID collected, in POSTed batches of LABEL_BATCH IDs using VALUES.
 * English labels win; Wikidata's newer "mul" (default for all languages) label is the fallback, since many given
 * name items now carry only that.
 */
public final class WikidataPull {
    static final String WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql";
    static final int LABEL_BATCH = 1000;
    /** Name class -> gender written to the file ("" for the generic class). */
    private static final Map<String, String> CLASSES = new java.util.LinkedHashMap<>();

    static {
        CLASSES.put("Q12308941", "male");   // male given name
        CLASSES.put("Q11879590", "female"); // female given name
        CLASSES.put("Q3409032", "unisex");  // unisex given name
        CLASSES.put("Q202444", "");         // given name
    }
    private static final String ENTITY = "http://www.wikidata.org/entity/";

    private static final String RELATIONS = """
            SELECT ?item ?lang ?same WHERE {
              ?item wdt:P31 wd:%s .
              OPTIONAL { ?item wdt:P407 ?lang }
              OPTIONAL { ?item wdt:P460 ?same }
            }""";

    private static final String LABELS = """
            SELECT ?item ?label WHERE {
              VALUES ?item { %s }
              ?item rdfs:label ?label .
              FILTER(LANG(?label) = "en" || LANG(?label) = "mul")
            }""";

    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private WikidataPull(String endpoint) {
        this.endpoint = endpoint;
    }

    /** One (item, language, same-as) triple of IDs plus the class's gender; language and same may be null. */
    private record Relation(String item, String lang, String same, String gender) {}

    public static void run(Path work, String endpoint) throws IOException, InterruptedException {
        Files.createDirectories(work);
        WikidataPull pull = new WikidataPull(endpoint);

        List<Relation> relations = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, String> cls : CLASSES.entrySet()) {
            int before = relations.size();
            for (JsonNode b : pull.query(RELATIONS.formatted(cls.getKey()))) {
                Relation r = new Relation(id(b, "item"), id(b, "lang"), id(b, "same"), cls.getValue());
                relations.add(r);
                ids.add(r.item());
                if (r.lang() != null) {
                    ids.add(r.lang());
                }
                if (r.same() != null) {
                    ids.add(r.same());
                }
            }
            System.out.printf("  %s: %,d rows%n", cls.getKey(), relations.size() - before);
            Thread.sleep(1000); // be polite to the shared endpoint
        }

        Map<String, String> label = pull.labels(new ArrayList<>(ids));
        long written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(work.resolve("wikidata.tsv"))) {
            for (Relation r : relations) {
                String name = label.get(r.item());
                if (name == null) {
                    continue;
                }
                out.write(String.join("\t", name,
                        r.lang() == null ? "" : label.getOrDefault(r.lang(), ""),
                        r.same() == null ? "" : label.getOrDefault(r.same(), ""),
                        r.gender()));
                out.newLine();
                written++;
            }
        }
        System.out.printf("done: %,d relation rows, %,d ids, %,d labelled, %,d rows written%n",
                relations.size(), ids.size(), label.size(), written);
    }

    private static String id(JsonNode binding, String var) {
        String uri = binding.path(var).path("value").asText(null);
        return uri == null ? null : uri.startsWith(ENTITY) ? uri.substring(ENTITY.length()) : null;
    }

    /** id -> label, English preferred over "mul". */
    private Map<String, String> labels(List<String> ids) throws IOException, InterruptedException {
        Map<String, String> en = new HashMap<>();
        Map<String, String> mul = new HashMap<>();
        for (int i = 0; i < ids.size(); i += LABEL_BATCH) {
            StringBuilder values = new StringBuilder();
            for (String id : ids.subList(i, Math.min(ids.size(), i + LABEL_BATCH))) {
                values.append("wd:").append(id).append(' ');
            }
            for (JsonNode b : query(LABELS.formatted(values))) {
                String id = id(b, "item");
                String lang = b.path("label").path("xml:lang").asText("");
                String text = b.path("label").path("value").asText("");
                (lang.equals("en") ? en : mul).put(id, text);
            }
            if ((i / LABEL_BATCH) % 20 == 0) {
                System.out.printf("  labels: %,d / %,d ids%n", Math.min(ids.size(), i + LABEL_BATCH), ids.size());
            }
            Thread.sleep(300);
        }
        mul.putAll(en);
        return mul;
    }

    /** POSTs a query (no URL length limit), retrying with back-off on the endpoint's 429/5xx answers. */
    private JsonNode query(String sparql) throws IOException, InterruptedException {
        String body = "query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("User-Agent", "name-origins-builder/1.0 (https://github.com/SanjithTammana/name-origins)")
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                try {
                    return json.readTree(resp.body()).path("results").path("bindings");
                } catch (IOException e) {
                    // a timeout mid-stream arrives as HTTP 200 followed by a Java stack trace: treat it as a retry
                    System.out.printf("    truncated response on attempt %d%n", attempt);
                }
            } else {
                System.out.printf("    HTTP %d on attempt %d%n", resp.statusCode(), attempt);
            }
            Thread.sleep(15_000L * attempt);
        }
        throw new IOException("Wikidata query failed after 5 attempts: " + sparql.substring(0, Math.min(120, sparql.length())));
    }

    public static void main(String[] args) throws Exception {
        run(Path.of(args.length > 0 ? args[0] : "work"), args.length > 1 ? args[1] : WIKIDATA_ENDPOINT);
    }
}
