package com.dashboard.todo;

import com.dashboard.todo.TodoDtos.CreateTodoRequest;
import com.dashboard.todo.TodoDtos.TodoResponse;
import com.dashboard.todo.TodoDtos.UpdateStatusRequest;
import com.dashboard.worker.LiveLogRegistry;
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

    public TodoController(TodoRepository repository, LiveLogRegistry liveLog) {
        this.repository = repository;
        this.liveLog = liveLog;
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
        todo.setStatus(TodoStatus.PENDING);
        return TodoResponse.from(repository.save(todo));
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
