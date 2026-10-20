package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void foldLowercasesAndStripsAccents() {
        assertEquals("jose", Text.fold("José"));
        assertEquals("zoe", Text.fold("Zoë"));
        assertEquals("francois", Text.fold("François"));
        assertEquals("lukasz", Text.fold("Łukasz"));
        assertEquals("soren", Text.fold("Søren"));
        assertEquals("strasse", Text.fold("Straße"));
        assertEquals("anne-marie", Text.fold("  Anne-Marie "));
    }

    @Test
    void foldLeavesNonLatinScriptsAlone() {
        assertEquals("さくら", Text.fold("さくら"));
    }

    @Test
    void transliterationVariantsShareAStrongKey() {
        assertEquals(SoundKey.strong("sanjith"), SoundKey.strong("sanjeet"));
        assertEquals(SoundKey.strong("sanjith"), SoundKey.strong("sanjit"));
        assertEquals(SoundKey.strong("sophia"), SoundKey.strong("sofia"));
        assertEquals(SoundKey.strong("sarah"), SoundKey.strong("sara"));
        assertEquals(SoundKey.strong("katherine"), SoundKey.strong("catherine"));
        assertEquals(SoundKey.strong("matthew"), SoundKey.strong("mathew"));
    }

    @Test
    void strongKeyKeepsDifferentNamesApart() {
        assertNotEquals(SoundKey.strong("sanjay"), SoundKey.strong("sanjit"));
        assertNotEquals(SoundKey.strong("emily"), SoundKey.strong("emma"));
    }

    @Test
    void skeletonKeyMatchesVowelVariants() {
        assertEquals(SoundKey.skeleton("mohammed"), SoundKey.skeleton("muhammad"));
        assertEquals(SoundKey.skeleton("yusuf"), SoundKey.skeleton("yousef"));
    }

    @Test
    void decimalParsesTheFormatsTheDataFilesUse() {
        assertEquals(-9.123f, Tsv.decimal("-9.123"), 1e-6);
        assertEquals(4f, Tsv.decimal("4"), 1e-6);
        assertEquals(0.5f, Tsv.decimal("0.500"), 1e-6);
        assertEquals(-0.7f, Tsv.decimal("-0.7"), 1e-6);
    }

    @Test
    void skeletonOfStrongMatchesSkeleton() {
        for (String n : new String[] {"mohammed", "yousef", "sanjith", "christopher", "xavier"}) {
            assertEquals(SoundKey.skeleton(n), SoundKey.skeletonOfStrong(SoundKey.strong(n)));
        }
    }

    @Test
    void tsvCleanRemovesBreakingCharactersAndLoneSurrogates() {
        assertEquals("a b", Tsv.clean("a\tb"));
        assertEquals("x", Tsv.clean("x\uD835"));          // high surrogate cut off at the end
        assertEquals("𝐀", Tsv.clean("𝐀")); // a valid pair stays
    }

    @Test
    void tokenizeDropsStopWordsAndPlurals() {
        assertEquals(List.of("wisdom", "divine"), Text.tokenize("The wisdom, of the divine!"));
        assertEquals(List.of("gift", "god"), Text.tokenize("gifts of God"));
    }
}
