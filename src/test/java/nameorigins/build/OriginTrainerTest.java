package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import nameorigins.core.OriginModel;
import org.junit.jupiter.api.Test;

class OriginTrainerTest {

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
