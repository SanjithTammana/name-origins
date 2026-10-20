package nameorigins.core;

/** An item with a score (a probability, a similarity, or a distance; the caller says which). */
public record Scored<T>(T item, double score) {}
