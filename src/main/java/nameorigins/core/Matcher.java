package nameorigins.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tries each matching layer in order and returns the first answer (Chain of Responsibility).
 * Adding a layer means adding a MatchStrategy; nothing here changes.
 */
public final class Matcher {

    /** One matching layer. Empty means "not my case, ask the next layer". */
    public interface MatchStrategy {
        Optional<MatchResult> match(String query, String folded);
    }

    private final List<MatchStrategy> layers;

    public Matcher(List<MatchStrategy> layers) {
        this.layers = layers;
    }

    /**
     * The standard layer order: exact, spelling variant, inferred origin, nearest names.
     * The model comes from a supplier so it can be parsed the first time a guess is needed (most lookups never
     * reach that layer); a supplier returning null skips the layer.
     */
    public static Matcher standard(NameDirectory dir, Supplier<OriginModel> model) {
        List<MatchStrategy> layers = new ArrayList<>();
        layers.add(new ExactLayer(dir));
        layers.add(new VariantLayer(dir));
        layers.add(new InferredLayer(dir, model));
        layers.add(new NearestLayer(dir));
        return new Matcher(layers);
    }

    public MatchResult match(String query) {
        String folded = Text.fold(query);
        for (MatchStrategy layer : layers) {
            Optional<MatchResult> r = layer.match(query, folded);
            if (r.isPresent()) {
                return r.get();
            }
        }
        return new MatchResult.None(query, List.of());
    }

    /** Exact hit that has origin data. A hit without origin falls through so a variant can do better. */
    static final class ExactLayer implements MatchStrategy {
        private final NameDirectory dir;

        ExactLayer(NameDirectory dir) {
            this.dir = dir;
        }

        @Override
        public Optional<MatchResult> match(String query, String folded) {
            int slot = dir.slot(folded);
            if (slot >= 0 && dir.hasOrigin(slot)) {
                return Optional.of(new MatchResult.Exact(query, folded));
            }
            return Optional.empty();
        }
    }

    /**
     * Spelling variants, strongest evidence first: the name's own variant group, then same strong sound key,
     * then same consonant skeleton (looser, so it must also be within a small edit distance). Variants with an
     * origin chain beat those with only an entry. Falls back to the typed name's own entry if it has one.
     */
    static final class VariantLayer implements MatchStrategy {
        private final NameDirectory dir;
        private final EditDistance ed = new EditDistance();

        VariantLayer(NameDirectory dir) {
            this.dir = dir;
        }

        @Override
        public Optional<MatchResult> match(String query, String folded) {
            int self = dir.slot(folded);
            List<Scored<Integer>> ranked = new ArrayList<>();
            if (self >= 0) {
                // A known name only follows its own recorded variants; sound-alike guessing is for unknown names
                // (otherwise "Wei", known from Wikidata, could be redirected to an unrelated "Vei").
                for (int v : dir.variantsOf(self)) {
                    add(ranked, v, 0, folded);
                }
            } else {
                for (int v : dir.soundsLike(folded)) {
                    add(ranked, v, 1, folded);
                }
                int skeletonLimit = Math.max(2, folded.length() / 3);
                for (int v : dir.skeletonLike(folded)) {
                    if (ed.within(folded, dir.key(v), skeletonLimit) <= skeletonLimit) {
                        add(ranked, v, 2, folded);
                    }
                }
            }
            // a variant is only worth following if it has something to show; ones with an origin chain rank first
            ranked.removeIf(s -> s.item() == self || !dir.hasEntries(s.item()));
            if (ranked.isEmpty()) {
                // the typed name itself: show it if it has entries; a bare recorded spelling falls through to the
                // guess / nearest layers instead of rendering an empty result
                boolean showSelf = self >= 0 && dir.hasEntries(self);
                return showSelf ? Optional.of(new MatchResult.Exact(query, folded)) : Optional.empty();
            }
            ranked.sort((a, b) -> {
                int c = Boolean.compare(dir.hasOrigin(b.item()), dir.hasOrigin(a.item()));
                return c != 0 ? c : Double.compare(a.score(), b.score());
            });
            List<String> alternatives = new ArrayList<>();
            for (int i = 1; i < ranked.size() && alternatives.size() < 5; i++) {
                alternatives.add(dir.display(ranked.get(i).item()));
            }
            return Optional.of(new MatchResult.Variant(query, dir.key(ranked.get(0).item()), alternatives));
        }

        /** Score = tier * 100 + edit distance, so tier dominates and distance breaks ties. */
        private void add(List<Scored<Integer>> ranked, int slot, int tier, String folded) {
            for (Scored<Integer> s : ranked) {
                if (s.item() == slot) {
                    return;
                }
            }
            ranked.add(new Scored<>(slot, tier * 100 + ed.within(folded, dir.key(slot), 50)));
        }
    }

    /** Guesses the origin language from letter patterns when no variant exists. */
    static final class InferredLayer implements MatchStrategy {
        private final NameDirectory dir;
        private final Supplier<OriginModel> modelSource;

        InferredLayer(NameDirectory dir, Supplier<OriginModel> model) {
            this.dir = dir;
            this.modelSource = model;
        }

        @Override
        public Optional<MatchResult> match(String query, String folded) {
            if (!Text.isLatinWord(folded) || folded.length() < 3) {
                return Optional.empty();
            }
            OriginModel model = modelSource.get();
            if (model == null) {
                return Optional.empty();
            }
            List<Scored<String>> origins = model.rank(folded, 3);
            if (origins.isEmpty() || origins.get(0).score() < model.confidenceFloor()) {
                return Optional.empty();
            }
            return Optional.of(new MatchResult.Inferred(query, origins,
                    model.evidence(folded, origins.get(0).item(), 4), NearestLayer.suggestions(dir, folded)));
        }
    }

    /** Last resort: the closest spellings that exist, so the visitor can click one. */
    static final class NearestLayer implements MatchStrategy {
        private final NameDirectory dir;

        NearestLayer(NameDirectory dir) {
            this.dir = dir;
        }

        @Override
        public Optional<MatchResult> match(String query, String folded) {
            return Optional.of(new MatchResult.None(query, suggestions(dir, folded)));
        }

        static List<String> suggestions(NameDirectory dir, String folded) {
            List<String> out = new ArrayList<>();
            if (Text.isLatinWord(folded) && !folded.isEmpty()) {
                for (Scored<Integer> s : dir.nearest(folded, 2, 5)) {
                    out.add(dir.display(s.item()));
                }
            }
            if (out.size() < 5 && folded.length() >= 2) {
                for (int slot : dir.withPrefix(folded.substring(0, Math.min(3, folded.length())), 10)) {
                    String d = dir.display(slot);
                    if (out.size() < 5 && !out.contains(d) && dir.hasOrigin(slot)) {
                        out.add(d);
                    }
                }
            }
            return out;
        }
    }
}
