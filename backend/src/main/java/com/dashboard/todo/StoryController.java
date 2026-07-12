package com.dashboard.todo;

import com.dashboard.todo.TodoDtos.CreateStoryRequest;
import com.dashboard.todo.TodoDtos.RelatedStory;
import com.dashboard.todo.TodoDtos.StoryResponse;
import com.dashboard.todo.TodoDtos.StoryStep;
import com.dashboard.todo.TodoDtos.SuggestStoryRequest;
import com.dashboard.todo.TodoDtos.SuggestStoryResponse;
import com.dashboard.todo.TodoDtos.TodoResponse;
import com.dashboard.todo.StorySuggestionService.SuggestedStory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates <em>stories</em> — named, ordered chains of tasks. Each step becomes a
 * normal todo tagged with a shared {@code storyId} and its position in the chain;
 * the worker then runs them one at a time, in order (a step waits until every
 * earlier step in its story is RESOLVED). Everything else (listing, live logs,
 * delete, retry) reuses the existing todo API.
 */
@RestController
@RequestMapping("/api/stories")
@CrossOrigin(origins = "*")
public class StoryController {

    private final TodoRepository repository;
    private final StorySuggestionService suggestions;

    public StoryController(TodoRepository repository, StorySuggestionService suggestions) {
        this.repository = repository;
        this.suggestions = suggestions;
    }

    /** Lists every story with its ordered steps, newest chain first. */
    @GetMapping
    public List<StoryResponse> list() {
        // Group the story-tagged todos by storyId, preserving the newest-first
        // order the query returns, then order each chain by its step index.
        List<StoryResponse> stories = new ArrayList<>();
        for (Map.Entry<String, List<Todo>> e : groupByStory().entrySet()) {
            List<Todo> steps = e.getValue();
            steps.sort((a, b) -> Integer.compare(
                    a.getStoryOrder() == null ? 0 : a.getStoryOrder(),
                    b.getStoryOrder() == null ? 0 : b.getStoryOrder()));
            stories.add(StoryResponse.from(e.getKey(), steps.get(0).getStoryName(), steps));
        }
        return stories;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<TodoResponse> create(@Valid @RequestBody CreateStoryRequest req) {
        String storyId = UUID.randomUUID().toString();
        String name = req.name().trim();
        List<TodoResponse> created = new ArrayList<>();
        int order = 0;
        for (StoryStep step : req.steps()) {
            Todo todo = new Todo();
            todo.setTitle(step.title().trim());
            todo.setDescription(step.description());
            todo.setStatus(TodoStatus.PENDING);
            todo.setStoryId(storyId);
            todo.setStoryName(name);
            todo.setStoryOrder(order++);
            created.add(TodoResponse.from(repository.save(todo)));
        }
        return created;
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
        List<RelatedStory> matches = new ArrayList<>();
        for (Map.Entry<String, List<Todo>> e : groupByStory().entrySet()) {
            List<Todo> steps = e.getValue();
            String name = steps.get(0).getStoryName();
            StringBuilder haystack = new StringBuilder(name == null ? "" : name);
            for (Todo step : steps) {
                haystack.append(' ').append(step.getTitle());
            }
            int score = suggestions.relatednessScore(text, haystack.toString());
            if (score > 0) {
                matches.add(new RelatedStory(e.getKey(), name, score));
            }
        }
        matches.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return matches.size() > 5 ? matches.subList(0, 5) : matches;
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
        if (steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story not found: " + storyId);
        }
        int nextOrder = 0;
        for (Todo s : steps) {
            if (s.getStoryOrder() != null && s.getStoryOrder() >= nextOrder) {
                nextOrder = s.getStoryOrder() + 1;
            }
        }
        Todo todo = new Todo();
        todo.setTitle(step.title().trim());
        todo.setDescription(step.description());
        todo.setStatus(TodoStatus.PENDING);
        todo.setStoryId(storyId);
        todo.setStoryName(steps.get(0).getStoryName());
        todo.setStoryOrder(nextOrder);
        return TodoResponse.from(repository.save(todo));
    }

    /** Story-tagged todos grouped by storyId, preserving newest-first order. */
    private Map<String, List<Todo>> groupByStory() {
        Map<String, List<Todo>> byStory = new LinkedHashMap<>();
        for (Todo t : repository.findByStoryIdIsNotNullOrderByCreatedAtDesc()) {
            byStory.computeIfAbsent(t.getStoryId(), k -> new ArrayList<>()).add(t);
        }
        return byStory;
    }
}
