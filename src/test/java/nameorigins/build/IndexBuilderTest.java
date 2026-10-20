package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import nameorigins.core.NameDirectory;
import nameorigins.core.NameEntry;
import nameorigins.core.Tsv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexBuilderTest {

    @Test
    void wikidataLinksJoinOnlySameSoundRespellings() {
        assertTrue(IndexBuilder.spellingClose("sofia", "sophia"));
        assertTrue(IndexBuilder.spellingClose("katherine", "catherine"));
        assertTrue(IndexBuilder.spellingClose("michael", "michaell"));
        assertFalse(IndexBuilder.spellingClose("michael", "mike"));      // nickname: a different name
        assertFalse(IndexBuilder.spellingClose("michael", "mikhail"));   // another language's form
        assertFalse(IndexBuilder.spellingClose("maria", "mario"));       // gendered counterpart
        assertFalse(IndexBuilder.spellingClose("wei", "mei"));           // different Chinese names
        assertFalse(IndexBuilder.spellingClose("adel", "adela"));        // the kind of step that chained 4,088 names
        assertFalse(IndexBuilder.spellingClose("wei", "伟"));             // non-Latin script
    }

    @Test
    void writesLfOnlyDataThatThePageCanRead(@TempDir Path tmp) throws Exception {
        // BufferedWriter.newLine() wrote \r\n on Windows, so a rebuild there broke the committed LF files
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path site = tmp.resolve("site");
        Files.writeString(work.resolve("langs.tsv"), "en\tEnglish\t9\nhbo\tBiblical Hebrew\t9\n");
        Files.writeString(work.resolve("names.jsonl"), "{\"word\":\"Michael\",\"lang\":\"English\",\"lang_code\":\"en\","
                + "\"etymology_templates\":[{\"name\":\"der\",\"args\":{\"1\":\"en\",\"2\":\"hbo\",\"3\":\"מִיכָאֵל\"},"
                + "\"expansion\":\"Biblical Hebrew מִיכָאֵל\"}],\"forms\":[{\"form\":\"Mykal\",\"tags\":[\"alternative\"]}],"
                + "\"senses\":[{\"glosses\":[\"A male given name\"]}]}\n");
        IndexBuilder.run(work, site);
        String dir = Files.readString(site.resolve("data/directory.tsv"));
        String shard = Files.readString(site.resolve("data/entries/m.tsv"));
        assertFalse(dir.contains("\r") || shard.contains("\r"), dir + shard);
        NameDirectory d = NameDirectory.parse(dir);
        int michael = d.slot("michael");
        assertTrue(michael >= 0 && d.hasOrigin(michael));
        assertTrue(d.slot("mykal") >= 0 && !d.hasEntries(d.slot("mykal")), "a recorded variant spelling");
        String[] f = Tsv.split(Tsv.lines(shard).get(0), '\t');
        assertEquals("Biblical Hebrew", NameEntry.fromTsv(f).chain().get(0).language());
    }
}
