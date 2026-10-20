package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
