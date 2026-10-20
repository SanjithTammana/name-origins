package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import nameorigins.core.OriginModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginTrainerTest {

    @TempDir Path temp;

    @Test
    void usageTrainingKeepsItsProvenanceAndExcludesAmbiguousNames() throws Exception {
        Path wikidata = temp.resolve("wikidata.tsv");
        Files.writeString(wikidata, "Eunwoo\tKorean\t\tmale\n"
                + "Kai\tKorean\t\tmale\nKai\tChinese\t\tmale\n"
                + "Raya\tSeediq\t\tfemale\nRaya\tAtayal\t\tfemale\n"
                + "Known\tKorean\t\tmale\nJane\tEnglish\t\tfemale\n");
        List<OriginTrainer.Example> examples = OriginTrainer.withUsage(
                List.of(new OriginTrainer.Example("known", "Known", "Greek")), wikidata);
        assertTrue(examples.contains(new OriginTrainer.Example("known", "Known", "Greek")));
        assertTrue(examples.contains(new OriginTrainer.Example("eunwoo", "Eunwoo", "used in Korean")));
        assertTrue(examples.contains(new OriginTrainer.Example("raya", "Raya", "used in Formosan languages")));
        assertTrue(examples.stream().noneMatch(e -> e.key().equals("kai") || e.key().equals("jane")));
        assertEquals(3, examples.size());
    }

    /** A model that is confidently wrong on every validation name must switch the guesser off, not pass at 0.95. */
    @Test
    void failedCalibrationDisablesTheGuesser() {
        OriginModel alwaysHebrew = OriginModel.parse("C\tHebrew\t0\t-1\nC\tLatin\t-5\t-1\nT\t1\n");
        List<OriginTrainer.Example> val = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            val.add(new OriginTrainer.Example("name" + (char) ('a' + i % 26), "x", "Latin"));
        }
        assertTrue(alwaysHebrew.rank("namea", 1).get(0).score() > 0.99);
        assertEquals(OriginTrainer.DISABLED, OriginTrainer.chooseFloor(alwaysHebrew, val));
    }

    @Test
    void goodCalibrationKeepsTheLowestHonestFloor() {
        OriginModel alwaysLatin = OriginModel.parse("C\tLatin\t0\t-1\nC\tHebrew\t-5\t-1\nT\t1\n");
        List<OriginTrainer.Example> val = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            val.add(new OriginTrainer.Example("name" + (char) ('a' + i % 26), "x", "Latin"));
        }
        assertEquals(0.20, OriginTrainer.chooseFloor(alwaysLatin, val), 1e-9);
    }

    @Test
    void familiesAndListsJoinTheirGroup() {
        assertEquals("Germanic", OriginGroups.of("Germanic"));
        assertEquals("Germanic", OriginGroups.of("Frankish"));
        assertEquals("Slavic", OriginGroups.of("Bulgarian, Macedonian, and Russian"));
        assertEquals("Celtic", OriginGroups.of("Irish and Scottish Gaelic"));
        // a list across groups, and a name that only looks like a list, keep their own name
        assertEquals("Portuguese and Spanish", OriginGroups.of("Portuguese and Spanish"));
        assertEquals("Antigua and Barbuda Creole English", OriginGroups.of("Antigua and Barbuda Creole English"));
    }
}
