package com.kanban.todo;

import com.kanban.todo.TodoDtos.RelatedStory;
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
    private final StoryRepository storyRepository;
    private final StorySuggestionService suggestions;

    public StoryClassifier(TodoRepository repository, StoryRepository storyRepository,
            StorySuggestionService suggestions) {
        this.repository = repository;
        this.storyRepository = storyRepository;
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
     * Every declared story, <em>including ones with no tasks yet</em>. Creating a
     * story only writes its {@link Story} row — it files no task — so a story is a
     * plain category that stays classifiable from the moment it's declared. Legacy
     * chains that predate the Story table have no row, so they are still surfaced
     * from their tagged todos.
     */
    public List<StoryView> candidates() {
        Map<String, List<Todo>> byStory = groupByStory();
        Map<String, StoryView> merged = new LinkedHashMap<>();
        for (Story s : storyRepository.findAll()) {
            merged.put(s.getId(), new StoryView(s.getId(), s.getName(),
                    byStory.getOrDefault(s.getId(), List.of())));
        }
        for (Map.Entry<String, List<Todo>> e : byStory.entrySet()) {
            merged.computeIfAbsent(e.getKey(),
                    id -> new StoryView(id, nameOf(e.getValue()), e.getValue()));
        }
        return new ArrayList<>(merged.values());
    }

    /** A story's name taken from its tagged todos (legacy chains with no row). */
    private static String nameOf(List<Todo> steps) {
        for (Todo t : steps) {
            if (t.getStoryName() != null) {
                return t.getStoryName();
            }
        }
        return "Story";
    }

    /**
     * Every existing story that {@code text} shares meaningful words with, best
     * match first, capped to the top few. Stories with no overlap are omitted.
     */
    public List<RelatedStory> related(String text) {
        List<RelatedStory> matches = new ArrayList<>();
        for (StoryView story : candidates()) {
            RelatedStory scored = score(text, story);
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
        for (StoryView story : candidates()) {
            RelatedStory scored = score(text, story);
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
    private RelatedStory score(String text, StoryView story) {
        String name = story.name();
        StringBuilder haystack = new StringBuilder(name == null ? "" : name);
        for (Todo step : story.steps()) {
            haystack.append(' ').append(step.getTitle());
        }
        return new RelatedStory(story.storyId(), name,
                suggestions.relatednessScore(text, haystack.toString()));
    }

    /**
     * One story as the classifiers see it: its id, its name, and whatever tasks are
     * filed under it — possibly none, since a story is just a category.
     */
    public record StoryView(String storyId, String name, List<Todo> steps) {
    }
}
