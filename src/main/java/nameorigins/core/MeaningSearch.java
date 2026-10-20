package nameorigins.core;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Finds names whose meaning is closest to some text ("victory", "gift of god").
 * Implementations: {@link TfIdfIndex} (word overlap) and {@link EmbeddingIndex} (word2vec vectors).
 */
public interface MeaningSearch {

    /** Best k names (keys) for the text, highest similarity first. */
    List<Scored<String>> search(String text, int k);

    /**
     * Keeps the k highest scores from a dense score array with a size-k min-heap: O(n log k) instead of sorting
     * all n. The heap's root is the weakest of the current top k, so each new score is compared with it once.
     */
    static List<Scored<String>> topK(float[] scores, String[] keys, int k) {
        PriorityQueue<Scored<String>> heap = new PriorityQueue<>(k + 1, (a, b) -> Double.compare(a.score(), b.score()));
        for (int i = 0; i < scores.length; i++) {
            float s = scores[i];
            if (s <= 0) {
                continue;
            }
            if (heap.size() < k) {
                heap.add(new Scored<>(keys[i], s));
            } else if (s > heap.peek().score()) {
                heap.poll();
                heap.add(new Scored<>(keys[i], s));
            }
        }
        List<Scored<String>> out = new ArrayList<>(heap);
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }
}
