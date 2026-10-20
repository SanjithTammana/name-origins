package nameorigins.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import nameorigins.core.Tsv;

/** Language code -> name, read from work/langs.tsv (code, name, votes) written by DataPass. */
final class LanguageNames {
    private LanguageNames() {}

    static Map<String, String> load(Path tsv) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(tsv)) {
            String[] f = Tsv.split(line, '\t');
            if (f.length >= 2) {
                out.put(f[0], f[1]);
            }
        }
        return out;
    }
}
