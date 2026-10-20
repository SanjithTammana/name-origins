package nameorigins.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The committed data in site/data, loaded once per test run and shared by the data contract and golden tests.
 * Files are decoded strictly (malformed UTF-8 throws), which is stricter than the browser.
 */
public final class SiteData {
    private SiteData() {}

    public static final Path DATA = Path.of("site", "data");

    private static final Map<String, String> TEXT = new HashMap<>();
    private static NameDirectory dir;
    private static OriginModel model;
    private static Matcher matcher;
    private static Map<String, List<NameEntry>> entries;

    /** A data file (path relative to site/data) as text, decoded strictly as UTF-8. */
    public static synchronized String text(String file) {
        return TEXT.computeIfAbsent(file, f -> {
            try {
                byte[] bytes = Files.readAllBytes(DATA.resolve(f));
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new AssertionError(f + " is not valid UTF-8", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public static List<String> lines(String file) {
        return Tsv.lines(text(file));
    }

    /** Every shard file name, e.g. "a.tsv" ... "z.tsv", "_.tsv". */
    public static List<String> shardFiles() {
        List<String> out = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) {
            out.add("entries/" + c + ".tsv");
        }
        out.add("entries/_.tsv");
        return out;
    }

    public static synchronized NameDirectory directory() {
        if (dir == null) {
            dir = NameDirectory.parse(text("directory.tsv"));
        }
        return dir;
    }

    public static synchronized OriginModel model() {
        if (model == null) {
            model = OriginModel.parse(text("model.tsv"));
        }
        return model;
    }

    /** A fresh standard matcher would rebuild every index, so one is shared. */
    public static synchronized Matcher matcher() {
        if (matcher == null) {
            matcher = Matcher.standard(directory(), SiteData::model);
        }
        return matcher;
    }

    /** Every entry row, grouped by key, as the page loads them (Data.entries). */
    public static synchronized Map<String, List<NameEntry>> entries() {
        if (entries == null) {
            entries = new HashMap<>();
            for (String file : shardFiles()) {
                for (String line : lines(file)) {
                    String[] f = Tsv.split(line, '\t');
                    entries.computeIfAbsent(f[0], k -> new ArrayList<>()).add(NameEntry.fromTsv(f));
                }
            }
        }
        return entries;
    }

    public static List<NameEntry> entries(String key) {
        return entries().getOrDefault(key, List.of());
    }
}
