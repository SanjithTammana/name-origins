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
import java.util.List;

/**
 * Pulls given names from Wikidata (CC0) once, into work/wikidata.tsv:
 *   label \t language-of-name label \t "said to be the same as" label
 * One row per combination (the optional fields may be empty).
 * Queried per name class and per first letter: one class in one request overran the public endpoint's
 * 60-second limit after 10 MB of output.
 */
public final class WikidataPull {
    /** The official endpoint 504s on this query under load; QLever (Univ. of Freiburg) serves the same data. */
    static final String WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String QLEVER_ENDPOINT = "https://qlever.cs.uni-freiburg.de/api/wikidata";
    private final String endpoint;
    private static final List<String> CLASSES = List.of(
            "Q12308941", // male given name
            "Q11879590", // female given name
            "Q3409032",  // unisex given name
            "Q202444");  // given name

    private static final String QUERY = """
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?label ?lang ?same WHERE {
              ?item wdt:P31 wd:%1$s ; rdfs:label ?label .
              FILTER(LANG(?label) = "en" && %2$s)
              OPTIONAL { ?item wdt:P407 ?l . ?l rdfs:label ?lang . FILTER(LANG(?lang) = "en") }
              OPTIONAL { ?item wdt:P460 ?s . ?s rdfs:label ?same . FILTER(LANG(?same) = "en") }
            }""";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL) // QLever answers with a 308 to its current API path
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private WikidataPull(String endpoint) {
        this.endpoint = endpoint;
    }

    public static void run(Path work, String endpoint) throws IOException, InterruptedException {
        Files.createDirectories(work);
        WikidataPull pull = new WikidataPull(endpoint);
        long rows = 0;
        try (BufferedWriter out = Files.newBufferedWriter(work.resolve("wikidata.tsv"))) {
            for (String cls : CLASSES) {
                for (String slice : slices()) {
                    int n = pull.fetch(cls, slice, out);
                    rows += n;
                    System.out.printf("  %s %s: %,d rows%n", cls, slice, n);
                    Thread.sleep(1000); // be polite to the shared endpoint
                }
            }
        }
        System.out.printf("done: %,d rows%n", rows);
    }

    private static List<String> slices() {
        List<String> slices = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            slices.add("STRSTARTS(?label, \"" + c + "\")");
        }
        slices.add("!REGEX(?label, \"^[A-Z]\")");
        return slices;
    }

    private int fetch(String cls, String slice, BufferedWriter out) throws IOException, InterruptedException {
        String q = URLEncoder.encode(QUERY.formatted(cls, slice), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "?query=" + q))
                .header("User-Agent", "name-origins-builder/1.0 (https://github.com/SanjithTammana/name-origins)")
                .header("Accept", "application/sparql-results+json")
                .timeout(Duration.ofSeconds(90))
                .build();
        HttpResponse<String> resp = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                break;
            }
            System.out.printf("    HTTP %d on attempt %d, waiting %ds%n", resp.statusCode(), attempt, 10 * attempt);
            Thread.sleep(10_000L * attempt); // the public endpoint 504s under load; back off and retry
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Wikidata " + cls + " " + slice + " returned HTTP " + resp.statusCode());
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(resp.body());
        } catch (IOException e) {
            String body = resp.body();
            throw new IOException("Wikidata " + cls + " " + slice + " sent unparseable JSON (" + body.length()
                    + " chars), ending: " + body.substring(Math.max(0, body.length() - 400)), e);
        }
        int n = 0;
        for (JsonNode b : parsed.path("results").path("bindings")) {
            out.write(String.join("\t",
                    b.path("label").path("value").asText(),
                    b.path("lang").path("value").asText(""),
                    b.path("same").path("value").asText("")));
            out.newLine();
            n++;
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        run(Path.of(args.length > 0 ? args[0] : "work"), args.length > 1 ? args[1] : QLEVER_ENDPOINT);
    }
}
