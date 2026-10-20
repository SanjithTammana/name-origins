package nameorigins.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caches the full source of English Wikipedia articles linked from Wikidata given-name items. A sitelink is
 * essential: looking up a bare title can land on a person, animal, or myth instead of the name article.
 * Source revision batches are kept under work/wikipedia-batches; a second run never refetches a valid batch.
 * The Action API only permits one full plain-text extract per request, so this uses full wikitext revisions in
 * batches of 20 and leaves interpretation to WikipediaNames.
 */
public final class WikipediaPull {
    private static final String[] CLASSES = {"male", "female", "unisex", "generic"};
    private static final String[] IDS = {"Q12308941", "Q11879590", "Q3409032", "Q202444"};
    private static final String SPARQL = "https://query.wikidata.org/sparql";
    private static final String API = "https://en.wikipedia.org/w/api.php";
    private static final String USER_AGENT = "name-origins-builder/1.0 (https://github.com/SanjithTammana/name-origins)";
    private static final int BATCH = 20;

    record Article(String id, String title, String label) {}

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private WikipediaPull() {}

    public static List<Article> loadArticles(Path work) throws IOException {
        WikipediaPull pull = new WikipediaPull();
        Map<String, Article> byId = new LinkedHashMap<>();
        for (String cls : CLASSES) {
            Path file = work.resolve("wikipedia-sitelinks-" + cls + ".json");
            if (!Files.exists(file)) {
                throw new IOException("Missing sitelink cache " + file + "; run WikipediaPull first");
            }
            for (JsonNode row : pull.json.readTree(file.toFile()).path("results").path("bindings")) {
                String uri = row.path("item").path("value").asText();
                String titleUri = row.path("article").path("value").asText();
                if (!uri.startsWith("http://www.wikidata.org/entity/Q")
                        || !titleUri.startsWith("https://en.wikipedia.org/wiki/")) {
                    continue;
                }
                String id = uri.substring(uri.lastIndexOf('/') + 1);
                String title = URLDecoder.decode(titleUri.substring(titleUri.lastIndexOf('/') + 1)
                        .replace('+', ' '), StandardCharsets.UTF_8).replace('_', ' ');
                String label = row.path("label").path("value").asText("");
                if (label.isEmpty()) {
                    label = title.replaceFirst(" \\(given name\\)$", "");
                }
                byId.putIfAbsent(id, new Article(id, title, label));
            }
        }
        List<Article> articles = new ArrayList<>(byId.values());
        articles.sort(Comparator.comparing(Article::id));
        return articles;
    }

    static Path batchFile(Path work, List<Article> batch, int number) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Article a : batch) {
                sha.update((a.id() + "\t" + a.title() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = sha.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return work.resolve("wikipedia-batches").resolve(String.format("%04d-%s.json", number, hex));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void run(Path work) throws IOException, InterruptedException {
        Files.createDirectories(work);
        WikipediaPull pull = new WikipediaPull();
        for (int i = 0; i < CLASSES.length; i++) {
            Path file = work.resolve("wikipedia-sitelinks-" + CLASSES[i] + ".json");
            if (!Files.exists(file)) {
                String query = "SELECT ?item ?article ?label WHERE { ?item wdt:P31 wd:" + IDS[i]
                        + " . ?article schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> . "
                        + "OPTIONAL { ?item rdfs:label ?label . FILTER(LANG(?label) = \"en\") } }";
                pull.fetch(SPARQL, "query=" + encode(query), "application/sparql-results+json", file);
                Thread.sleep(1000);
            }
        }
        List<Article> articles = loadArticles(work);
        Files.createDirectories(work.resolve("wikipedia-batches"));
        System.out.printf("Wikipedia sitelinks: %,d given-name articles%n", articles.size());
        int fetched = 0;
        for (int start = 0; start < articles.size(); start += BATCH) {
            int number = start / BATCH;
            List<Article> batch = articles.subList(start, Math.min(articles.size(), start + BATCH));
            Path file = batchFile(work, batch, number);
            if (Files.exists(file)) {
                try {
                    if (pull.json.readTree(file.toFile()).path("query").path("pages").isArray()) {
                        continue;
                    }
                } catch (IOException ignored) {
                    // A partial or corrupt cache is replaced by a complete response.
                }
            }
            StringBuilder titles = new StringBuilder();
            for (Article a : batch) {
                if (!titles.isEmpty()) titles.append('|');
                titles.append(a.title());
            }
            String body = "action=query&prop=revisions&rvslots=main&rvprop=content&redirects=1"
                    + "&format=json&formatversion=2&maxlag=5&titles=" + encode(titles.toString());
            pull.fetch(API, body, "application/json", file);
            fetched++;
            if (fetched % 25 == 0 || start + BATCH >= articles.size()) {
                System.out.printf("  fetched %,d / %,d articles (%,d new batches)%n",
                        Math.min(articles.size(), start + BATCH), articles.size(), fetched);
            }
            Thread.sleep(250); // serial, low-pressure requests to a shared public API
        }
    }

    private void fetch(String endpoint, String body, String accept, Path file) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("User-Agent", USER_AGENT).header("Accept", accept)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(90)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                JsonNode root;
                try {
                    root = json.readTree(response.body());
                } catch (IOException e) {
                    root = null;
                }
                if (root != null && !root.has("error") && root.path("query").path("pages").isArray()
                        || root != null && !root.has("error") && root.path("results").path("bindings").isArray()) {
                    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(temp, response.body());
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            long backoff = Math.min(60, 3L * attempt * attempt);
            System.err.printf("  %s batch retry %d (HTTP %d); waiting %ds%n",
                    endpoint, attempt, response.statusCode(), backoff);
            Thread.sleep(backoff * 1000);
        }
        throw new IOException("Wikipedia request failed after retries: " + file);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        run(Path.of(args.length > 0 ? args[0] : "work"));
    }
}
