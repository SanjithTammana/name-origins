package nameorigins.core;

/** One hop in a name's history: the language and the word form it had there (form may be empty). */
public record OriginStep(String language, String form) {}
