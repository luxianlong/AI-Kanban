package com.dashboard.todo;

import com.dashboard.todo.TodoDtos.CreateStoryRequest;
import com.dashboard.todo.TodoDtos.StoryResponse;
import com.dashboard.todo.TodoDtos.StoryStep;
import com.dashboard.todo.TodoDtos.TodoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    public StoryController(TodoRepository repository) {
        this.repository = repository;
    }

    /** Lists every story with its ordered steps, newest chain first. */
    @GetMapping
    public List<StoryResponse> list() {
        // Group the story-tagged todos by storyId, preserving the newest-first
        // order the query returns, then order each chain by its step index.
        Map<String, List<Todo>> byStory = new LinkedHashMap<>();
        for (Todo t : repository.findByStoryIdIsNotNullOrderByCreatedAtDesc()) {
            byStory.computeIfAbsent(t.getStoryId(), k -> new ArrayList<>()).add(t);
        }
        List<StoryResponse> stories = new ArrayList<>();
        for (Map.Entry<String, List<Todo>> e : byStory.entrySet()) {
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
}
