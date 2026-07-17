package com.kanban.todo;

import com.kanban.todo.TodoDtos.CreateStoryRequest;
import com.kanban.todo.TodoDtos.RelatedStory;
import com.kanban.todo.TodoDtos.StoryResponse;
import com.kanban.todo.TodoDtos.StoryStep;
import com.kanban.todo.TodoDtos.SuggestStoryRequest;
import com.kanban.todo.TodoDtos.SuggestStoryResponse;
import com.kanban.todo.TodoDtos.TodoResponse;
import com.kanban.todo.StorySuggestionService.SuggestedStory;
import com.kanban.worker.LiveLogRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates <em>stories</em> — a high-level goal the worker decomposes into an
 * ordered chain of tasks. Creating a story files one {@code storyPlanner} seed
 * (see {@link #create}); when the worker runs it, the agent appends concrete step
 * tasks via {@link #addStep}, each tagged with the shared {@code storyId} and its
 * position in the chain. The worker then runs the steps one at a time, in order (a
 * step waits until every earlier step in its story is RESOLVED). Everything else
 * (listing, live logs, delete, retry) reuses the existing todo API.
 */
@RestController
@RequestMapping("/api/stories")
@CrossOrigin(origins = "*")
public class StoryController {

    private final TodoRepository repository;
    private final StoryRepository storyRepository;
    private final StorySuggestionService suggestions;
    private final StoryClassifier classifier;
    private final LiveLogRegistry liveLog;

    public StoryController(TodoRepository repository, StoryRepository storyRepository,
            StorySuggestionService suggestions, StoryClassifier classifier, LiveLogRegistry liveLog) {
        this.repository = repository;
        this.storyRepository = storyRepository;
        this.suggestions = suggestions;
        this.classifier = classifier;
        this.liveLog = liveLog;
    }

    /**
     * Lists every story with its ordered steps, newest chain first. A story with
     * <em>no</em> tasks is still listed (with an empty {@code steps}) as long as its
     * {@link Story} row exists — that's what lets an empty chain stay on the board
     * instead of vanishing when its last task is cleared.
     */
    @GetMapping
    public List<StoryResponse> list() {
        // Order each chain by step index up front, keyed by storyId.
        Map<String, List<Todo>> byStory = classifier.groupByStory();
        for (List<Todo> steps : byStory.values()) {
            steps.sort((a, b) -> Integer.compare(
                    a.getStoryOrder() == null ? 0 : a.getStoryOrder(),
                    b.getStoryOrder() == null ? 0 : b.getStoryOrder()));
        }
        // Merge persisted stories (which may have no tasks) with any story-tagged
        // todos. A persisted Story pins name + createdAt; legacy stories created
        // before the Story table existed have todos but no row, so we still surface
        // them from their steps.
        Map<String, StoryAgg> merged = new LinkedHashMap<>();
        for (Story s : storyRepository.findAll()) {
            merged.put(s.getId(), new StoryAgg(s.getName(), s.getCreatedAt(), new ArrayList<>()));
        }
        for (Map.Entry<String, List<Todo>> e : byStory.entrySet()) {
            List<Todo> steps = e.getValue();
            StoryAgg agg = merged.get(e.getKey());
            if (agg == null) {
                merged.put(e.getKey(), new StoryAgg(nameOf(steps), createdAtOf(steps), steps));
            } else {
                agg.steps = steps;
                if (agg.name == null) {
                    agg.name = nameOf(steps);
                }
            }
        }
        // Newest activity first: a story sorts by the latest of its own createdAt
        // and its newest step, so a freshly-touched chain floats to the top. An
        // empty story keeps its own createdAt so it doesn't sink to the bottom.
        List<StoryResponse> stories = new ArrayList<>();
        merged.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, StoryAgg> e) -> sortKey(e.getValue()))
                        .reversed())
                .forEach(e -> {
                    StoryAgg agg = e.getValue();
                    stories.add(StoryResponse.from(e.getKey(), agg.name, agg.createdAt, agg.steps));
                });
        return stories;
    }

    /** Latest activity on a story: max of its own createdAt and its newest step. */
    private static Instant sortKey(StoryAgg agg) {
        Instant key = agg.createdAt == null ? Instant.EPOCH : agg.createdAt;
        for (Todo step : agg.steps) {
            if (step.getCreatedAt() != null && step.getCreatedAt().isAfter(key)) {
                key = step.getCreatedAt();
            }
        }
        return key;
    }

    private static String nameOf(List<Todo> steps) {
        for (Todo t : steps) {
            if (t.getStoryName() != null) {
                return t.getStoryName();
            }
        }
        return "Story";
    }

    private static Instant createdAtOf(List<Todo> steps) {
        return steps.isEmpty() ? Instant.EPOCH : steps.get(0).getCreatedAt();
    }

    /** Mutable accumulator while merging persisted stories with their steps. */
    private static final class StoryAgg {
        private String name;
        private final Instant createdAt;
        private List<Todo> steps;

        StoryAgg(String name, Instant createdAt, List<Todo> steps) {
            this.name = name;
            this.createdAt = createdAt;
            this.steps = steps;
        }
    }

    /**
     * Create a story from a single one-line goal. Instead of taking pre-written
     * steps, this files ONE planner task: a seed tagged with a fresh storyId and
     * marked {@code storyPlanner}. It's queued (PENDING) immediately, so the worker
     * picks it up on its next poll and decomposes the goal into concrete step tasks
     * (filed under the same storyId, queued and run in chain order).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@Valid @RequestBody CreateStoryRequest req) {
        String storyId = UUID.randomUUID().toString();
        String name = req.name().trim();
        // Persist the story container first, so the story exists in its own right
        // (and survives with no tasks) independently of the planner/step todos.
        storyRepository.save(new Story(storyId, name));
        Todo planner = new Todo();
        planner.setTitle(name);
        planner.setDescription(req.description());
        planner.setStoryId(storyId);
        planner.setStoryName(name);
        planner.setStoryPlanner(true);
        // No storyOrder: the planner is the seed, not a numbered step. Queue it so
        // the worker plans the story right away.
        planner.setStatus(TodoStatus.PENDING);
        return TodoResponse.from(repository.save(planner));
    }

    /**
     * Decompose a free-text goal into an ordered set of suggested steps. The
     * client shows these in the story composer so the user can tweak and submit
     * them instead of hand-typing the whole chain.
     */
    @PostMapping("/suggest")
    public SuggestStoryResponse suggest(@Valid @RequestBody SuggestStoryRequest req) {
        SuggestedStory s = suggestions.suggest(req.goal());
        return new SuggestStoryResponse(s.name(), s.steps());
    }

    /**
     * Existing stories that {@code text} looks related to, most relevant first.
     * Lets a client offer "attach this task to story X" instead of forcing every
     * task to start its own chain. Only stories with a non-zero overlap are
     * returned, capped to the top few.
     */
    @GetMapping("/related")
    public List<RelatedStory> related(@RequestParam String text) {
        return classifier.related(text);
    }

    /**
     * Append a task to the end of an existing story. This is how a task gets
     * "attached to a related story": it becomes the next step in the chain, so
     * the worker runs it after everything already queued there.
     */
    @PostMapping("/{storyId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse addStep(@PathVariable String storyId, @Valid @RequestBody StoryStep step) {
        List<Todo> steps = repository.findByStoryIdOrderByStoryOrderAsc(storyId);
        // The story must exist — either as a persisted Story row (which may still be
        // empty) or, for legacy chains, via its existing tagged todos.
        if (steps.isEmpty() && !storyRepository.existsById(storyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story not found: " + storyId);
        }
        Todo todo = new Todo();
        todo.setTitle(step.title().trim());
        todo.setDescription(step.description());
        // Appended steps queue (PENDING) like every task; the worker runs them in
        // chain order (a step waits for every earlier step in its story to RESOLVE).
        todo.setStatus(TodoStatus.PENDING);
        todo.setStoryId(storyId);
        todo.setStoryName(steps.isEmpty()
                ? storyRepository.findById(storyId).map(Story::getName).orElse("Story")
                : steps.get(0).getStoryName());
        todo.setStoryOrder(classifier.nextOrder(steps));
        return TodoResponse.from(repository.save(todo));
    }

    /**
     * Delete a story <em>and</em> its tasks. This is the explicit, user-driven way
     * to remove a story (an empty one won't disappear on its own). Every task tagged
     * with the story is deleted along with the container, and the story's Story row
     * is removed. 404 if the story is unknown (no row and no tagged tasks).
     */
    @DeleteMapping("/{storyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String storyId) {
        List<Todo> steps = repository.findByStoryIdOrderByStoryOrderAsc(storyId);
        boolean hasRow = storyRepository.existsById(storyId);
        if (steps.isEmpty() && !hasRow) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story not found: " + storyId);
        }
        for (Todo t : steps) {
            liveLog.clear(t.getId());
        }
        repository.deleteAll(steps);
        if (hasRow) {
            storyRepository.deleteById(storyId);
        }
    }
}
