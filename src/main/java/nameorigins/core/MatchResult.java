package nameorigins.core;

import java.util.List;

/**
 * What the matcher concluded about a typed name. Sealed, so the UI's switch over it must handle every case
 * and the compiler checks that it does. Results carry keys, not entries: the UI loads the entry data for a
 * key separately, which keeps matching synchronous and free of network code.
 */
public sealed interface MatchResult {
    String query();

    /** The name is in the data. */
    record Exact(String query, String key) implements MatchResult {}

    /** Not in the data as typed, but a spelling variant is ("Sanjith" -> "sanjeet"). */
    record Variant(String query, String key, List<String> alternatives) implements MatchResult {}

    /** No variant either; the origin is guessed from letter patterns. origins are probabilities, best first. */
    record Inferred(String query, List<Scored<String>> origins, List<String> evidence, List<String> suggestions)
            implements MatchResult {}

    /** Nothing confident; closest names instead. */
    record None(String query, List<String> suggestions) implements MatchResult {}
}
