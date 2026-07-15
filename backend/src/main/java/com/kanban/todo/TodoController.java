package com.kanban.todo;

import com.kanban.todo.TodoDtos.CreateTodoRequest;
import com.kanban.todo.TodoDtos.SetStoryRequest;
import com.kanban.todo.TodoDtos.TodoResponse;
import com.kanban.todo.TodoDtos.UpdateStatusRequest;
import com.kanban.worker.LiveLogRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST API for todo items. The frontend uses this to create/list/delete todos;
 * the Claude Code worker uses {@code PATCH} to move items through their lifecycle.
 */
@RestController
@RequestMapping("/api/todos")
@CrossOrigin(origins = "*")
public class TodoController {

    private final TodoRepository repository;
    private final LiveLogRegistry liveLog;
    private final StoryClassifier classifier;
    private final AiStoryClassifier aiClassifier;

    public TodoController(TodoRepository repository, LiveLogRegistry liveLog,
                          StoryClassifier classifier, AiStoryClassifier aiClassifier) {
        this.repository = repository;
        this.liveLog = liveLog;
        this.classifier = classifier;
        this.aiClassifier = aiClassifier;
    }

    @GetMapping
    public List<TodoResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(TodoResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public TodoResponse get(@PathVariable Long id) {
        return TodoResponse.from(find(id));
    }

    /**
     * Live session transcript for a todo — the running log of what the Claude
     * session is doing. Useful to poll while the item is IN_PROGRESS.
     */
    @GetMapping("/{id}/log")
    public Map<String, Object> log(@PathVariable Long id) {
        Todo todo = find(id);
        return Map.of(
                "id", todo.getId(),
                "status", todo.getStatus(),
                "log", liveLog.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@Valid @RequestBody CreateTodoRequest req) {
        Todo todo = new Todo();
        todo.setTitle(req.title().trim());
        todo.setDescription(req.description());
        // Every task queues straight to To Do (PENDING) — there's no separate
        // staging step. A task that joins a story still runs in chain order: the
        // worker holds each story step until every earlier step has RESOLVED.
        todo.setStatus(TodoStatus.PENDING);
        // Optional flag; a plain task defaults to a general (non-project) task.
        todo.setProjectImprovement(Boolean.TRUE.equals(req.projectImprovement()));
        // If the user has declared stories, classify this new task against them:
        // file it under the story it most clearly belongs to, or leave it
        // standalone when nothing fits. A story-tagged task queues (PENDING) and
        // runs in its story's chain order.
        String text = (todo.getTitle() + " "
                + (req.description() == null ? "" : req.description())).trim();
        classifier.classify(text).ifPresent(story -> {
            todo.setStoryId(story.storyId());
            todo.setStoryName(story.name());
            todo.setStoryOrder(classifier.nextOrder(
                    repository.findByStoryIdOrderByStoryOrderAsc(story.storyId())));
        });
        // If the fast keyword pass left it standalone and the smart pass is on,
        // mark it `classifying` so the worker skips it during the few seconds the
        // background Claude classification takes. The async pass then either files
        // it into a story or releases it to run standalone, clearing the flag
        // either way. Without this, the fast worker poll routinely beats
        // classification and the task runs before it can be filed.
        boolean smartPass = todo.getStoryId() == null && aiClassifier.isEnabled();
        if (smartPass) {
            todo.setClassifying(true);
        }
        Todo saved = repository.save(todo);
        if (smartPass) {
            aiClassifier.fileAsync(saved.getId(), text);
        }
        return TodoResponse.from(saved);
    }

    @PatchMapping("/{id}")
    public TodoResponse updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        Todo todo = find(id);
        TodoStatus status;
        try {
            status = TodoStatus.valueOf(req.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + req.status());
        }
        todo.setStatus(status);
        if (req.result() != null) {
            todo.setResult(req.result());
        }
        return TodoResponse.from(repository.save(todo));
    }

    /**
     * Manually file a task under a story (or unassign it). A null/blank storyId
     * makes the task standalone; otherwise the story must exist and the task is
     * appended to the end of that chain. The task's status is left unchanged — the
     * user is organising, not re-running it.
     */
    @PatchMapping("/{id}/story")
    public TodoResponse setStory(@PathVariable Long id, @RequestBody SetStoryRequest req) {
        Todo todo = find(id);
        String storyId = req.storyId() == null ? null : req.storyId().trim();
        if (storyId == null || storyId.isEmpty()) {
            todo.setStoryId(null);
            todo.setStoryName(null);
            todo.setStoryOrder(null);
        } else {
            List<Todo> steps = repository.findByStoryIdOrderByStoryOrderAsc(storyId);
            if (steps.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story not found: " + storyId);
            }
            String name = "Story";
            for (Todo s : steps) {
                if (s.getStoryName() != null) {
                    name = s.getStoryName();
                    break;
                }
            }
            todo.setStoryId(storyId);
            todo.setStoryName(name);
            todo.setStoryOrder(classifier.nextOrder(steps)); // append at the chain's end
        }
        return TodoResponse.from(repository.save(todo));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found: " + id);
        }
        repository.deleteById(id);
        liveLog.clear(id);
    }

    private Todo find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found: " + id));
    }
}
