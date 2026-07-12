package com.dashboard.todo;

import com.dashboard.todo.TodoDtos.RelatedStory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which existing story, if any, a piece of text belongs to. This is the
 * counterpart to {@link StorySuggestionService}: that service scores a single
 * text/story pair, this one walks every declared story and picks the matches.
 *
 * <p>Two uses sit on the same scoring: the task composer asks {@link #related}
 * for every story a draft <em>could</em> attach to (so it can offer chips), while
 * task creation asks {@link #classify} for the one story a new task most clearly
 * <em>does</em> belong to, and files it there automatically.
 */
@Service
public class StoryClassifier {

    /**
     * Minimum shared-keyword score before a freshly created task is auto-filed
     * under a story. The "related" chips surface anything with a non-zero overlap,
     * but automatically classifying a task into a story is a stronger commitment —
     * it changes the order the worker runs the task in — so it takes more than one
     * incidental word in common before we adopt the task into a chain.
     */
    static final int AUTO_CLASSIFY_MIN_SCORE = 2;

    private final TodoRepository repository;
    private final StorySuggestionService suggestions;

    public StoryClassifier(TodoRepository repository, StorySuggestionService suggestions) {
        this.repository = repository;
        this.suggestions = suggestions;
    }

    /** Story-tagged todos grouped by storyId, preserving newest-first order. */
    public Map<String, List<Todo>> groupByStory() {
        Map<String, List<Todo>> byStory = new LinkedHashMap<>();
        for (Todo t : repository.findByStoryIdIsNotNullOrderByCreatedAtDesc()) {
            byStory.computeIfAbsent(t.getStoryId(), k -> new ArrayList<>()).add(t);
        }
        return byStory;
    }

    /**
     * Every existing story that {@code text} shares meaningful words with, best
     * match first, capped to the top few. Stories with no overlap are omitted.
     */
    public List<RelatedStory> related(String text) {
        List<RelatedStory> matches = new ArrayList<>();
        for (Map.Entry<String, List<Todo>> e : groupByStory().entrySet()) {
            RelatedStory scored = score(text, e.getKey(), e.getValue());
            if (scored.score() > 0) {
                matches.add(scored);
            }
        }
        matches.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return matches.size() > 5 ? matches.subList(0, 5) : matches;
    }

    /**
     * The one story {@code text} most clearly belongs to, or empty when no story
     * clears {@link #AUTO_CLASSIFY_MIN_SCORE}. Used to auto-file a newly created
     * task under an existing story instead of leaving it standalone.
     */
    public Optional<RelatedStory> classify(String text) {
        RelatedStory best = null;
        for (Map.Entry<String, List<Todo>> e : groupByStory().entrySet()) {
            RelatedStory scored = score(text, e.getKey(), e.getValue());
            if (scored.score() >= AUTO_CLASSIFY_MIN_SCORE
                    && (best == null || scored.score() > best.score())) {
                best = scored;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The next free step index at the end of a story's chain. */
    public int nextOrder(List<Todo> steps) {
        int next = 0;
        for (Todo s : steps) {
            if (s.getStoryOrder() != null && s.getStoryOrder() >= next) {
                next = s.getStoryOrder() + 1;
            }
        }
        return next;
    }

    /** Score {@code text} against one story (its name joined with its step titles). */
    private RelatedStory score(String text, String storyId, List<Todo> steps) {
        String name = steps.get(0).getStoryName();
        StringBuilder haystack = new StringBuilder(name == null ? "" : name);
        for (Todo step : steps) {
            haystack.append(' ').append(step.getTitle());
        }
        return new RelatedStory(storyId, name, suggestions.relatednessScore(text, haystack.toString()));
    }
}
