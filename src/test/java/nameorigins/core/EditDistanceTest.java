package nameorigins.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EditDistanceTest {

    private final EditDistance d = new EditDistance();

    @Test
    void exactDistancesWithinTheLimit() {
        assertEquals(0, d.within("anna", "anna", 2));
        assertEquals(1, d.within("anna", "ana", 2));
        assertEquals(2, d.within("sanjit", "sanjeet", 2));
        assertEquals(3, d.within("sanjith", "sanjeet", 2)); // i->e, +e, -h: beyond 2, which is why sound keys exist
        assertEquals(1, d.within("", "a", 2));
    }

    @Test
    void returnsLimitPlusOneWhenFurther() {
        assertEquals(3, d.within("michael", "miguel", 2));
        assertEquals(3, d.within("a", "abcdef", 2));
    }

    @Test
    void agreesWithFullLevenshteinOnALargerLimit() {
        assertEquals(3, d.within("kitten", "sitting", 10));
        assertEquals(6, d.within("", "sophia", 10));
    }
}
