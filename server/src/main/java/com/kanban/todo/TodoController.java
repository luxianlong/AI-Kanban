package com.kanban.todo;

import com.kanban.attachment.AttachmentDtos.AttachmentView;
import com.kanban.attachment.AttachmentService;
import com.kanban.todo.TodoDtos.CreateTodoRequest;
import com.kanban.todo.TodoDtos.RerunTodoRequest;
import com.kanban.todo.TodoDtos.SetStoryRequest;
import com.kanban.todo.TodoDtos.TodoResponse;
import com.kanban.todo.TodoDtos.UpdateStatusRequest;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.RunningSessionRegistry;
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
    private final StoryRepository storyRepository;
    private final LiveLogRegistry liveLog;
    private final RunningSessionRegistry sessions;
    private final StoryClassifier classifier;
    private final AiStoryClassifier aiClassifier;
    private final AttachmentService attachments;

    public TodoController(TodoRepository repository, StoryRepository storyRepository,
                          LiveLogRegistry liveLog, RunningSessionRegistry sessions,
                          StoryClassifier classifier, AiStoryClassifier aiClassifier,
                          AttachmentService attachments) {
        this.repository = repository;
        this.storyRepository = storyRepository;
        this.liveLog = liveLog;
        this.sessions = sessions;
        this.classifier = classifier;
        this.aiClassifier = aiClassifier;
        this.attachments = attachments;
    }

    @GetMapping
    public List<TodoResponse> list() {
        List<Todo> todos = repository.findAllByOrderByCreatedAtDesc();
        // One query for every task's attachments rather than one per card.
        Map<Long, List<AttachmentView>> byTodo =
                attachments.viewsForAll(todos.stream().map(Todo::getId).toList());
        return todos.stream()
                .map(t -> TodoResponse.from(t, byTodo.get(t.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    public TodoResponse get(@PathVariable Long id) {
        return withAttachments(find(id));
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

    /**
     * Stop the running Claude session for an in-progress task, on user request.
     * Forcibly terminates the live process; the worker then records the task as
     * FAILED ("Stopped by user request") and it lands in Needs Attention, from
     * where it can be requeued. Only valid while the task is IN_PROGRESS with a
     * live session — otherwise there is nothing to stop (409).
     */
    @PostMapping("/{id}/stop")
    public TodoResponse stop(@PathVariable Long id) {
        Todo todo = find(id);
        if (todo.getStatus() != TodoStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task #" + id + " is not running (status " + todo.getStatus() + ").");
        }
        if (!sessions.stop(id)) {
            // IN_PROGRESS but no live process: the session died with a previous
            // backend, so there is nothing here to kill.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No live session found for task #" + id + " to stop.");
        }
        liveLog.append(id, "● Stop requested by user — terminating the session…");
        // The worker flips the task to FAILED once the killed process is reaped;
        // return the current (still IN_PROGRESS) row and let the UI poll it through.
        return withAttachments(todo);
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
        // A delegated (代理) task is queued like any other, but the worker never
        // picks it up: it waits in To Do until an outside worker finds it through
        // the public API and reports the outcome back.
        todo.setDelegated(Boolean.TRUE.equals(req.delegated()));
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
        // Bind any images the user uploaded while composing to this task, now that
        // it has an id. The worker hands their paths to the agent, so the session
        // resolving the task can actually open them.
        List<AttachmentView> attached = attachments.attach(req.attachmentIds(), saved.getId())
                .stream().map(AttachmentView::from).toList();
        if (smartPass) {
            aiClassifier.fileAsync(saved.getId(), text);
        }
        return TodoResponse.from(saved, attached);
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
        // Maintain the unread-notification flag on the status edge: a task becomes
        // an unread notification the moment it crosses into a terminal state, and
        // stops being one when it's requeued/rerun (so a retried task shows up as
        // unread again only once it finishes anew). Because unread lives on the
        // row, that badge state is shared across every device the user opens.
        TodoStatus previous = todo.getStatus();
        boolean nowTerminal = status == TodoStatus.RESOLVED || status == TodoStatus.FAILED;
        if (nowTerminal && status != previous) {
            todo.setUnread(true);
        } else if (!nowTerminal) {
            todo.setUnread(false);
        }
        todo.setStatus(status);
        if (req.result() != null) {
            todo.setResult(req.result());
        }
        return withAttachments(repository.save(todo));
    }

    /**
     * Re-run a finished task as a <em>copy</em>: queue a brand-new PENDING task
     * built from this one, leaving the original untouched.
     *
     * <p>Requeuing the same row used to be the only way to run something again,
     * which overwrote the previous run's result and lost the history of what was
     * tried. A copy keeps the first attempt readable next to the second, and —
     * because the client may send an edited title/description — lets the user fix
     * the instructions that made the first run fail before running it again.
     *
     * <p>Everything that describes the <em>work</em> is carried over (title,
     * description, project/delegated flags, story membership, attachments);
     * everything that describes the finished <em>run</em> is not (result, cost,
     * duration, timestamps). A copy of a story step is appended to the end of its
     * story's chain, so it runs after the steps already there rather than
     * re-opening a slot in the middle.
     */
    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse rerun(@PathVariable Long id, @Valid @RequestBody(required = false) RerunTodoRequest req) {
        Todo source = find(id);
        String title = req == null ? null : req.title();
        String description = req == null ? null : req.description();

        Todo copy = new Todo();
        copy.setTitle(title == null || title.isBlank() ? source.getTitle() : title.trim());
        // An omitted description keeps the original's; an explicitly empty one is
        // the user deleting it in the form, so it is honoured as written.
        copy.setDescription(description == null ? source.getDescription() : description);
        copy.setStatus(TodoStatus.PENDING);
        copy.setProjectImprovement(source.isProjectImprovement());
        copy.setDelegated(source.isDelegated());
        copy.setStoryPlanner(source.isStoryPlanner());
        if (source.getStoryId() != null) {
            copy.setStoryId(source.getStoryId());
            copy.setStoryName(source.getStoryName());
            // A planner has no numbered slot; a step copy queues after the chain's
            // current last step so the story still runs strictly in order.
            if (!source.isStoryPlanner()) {
                copy.setStoryOrder(classifier.nextOrder(
                        repository.findByStoryIdOrderByStoryOrderAsc(source.getStoryId())));
            }
        }
        Todo saved = repository.save(copy);
        // Give the new task its own copies of the original's images, so the agent
        // running it can open them and deleting either task leaves the other whole.
        List<AttachmentView> copied = attachments.copyTo(source.getId(), saved.getId())
                .stream().map(AttachmentView::from).toList();
        return TodoResponse.from(saved, copied);
    }

    /**
     * Mark a finished task as read, clearing its unread-notification flag. Called
     * when the user opens the task's detail (from the board or the notification
     * center). Idempotent and safe on any task — a non-unread task just stays
     * cleared. Because the flag is persisted, reading it on one device clears the
     * badge on every device.
     */
    @PostMapping("/{id}/read")
    public TodoResponse markRead(@PathVariable Long id) {
        Todo todo = find(id);
        if (todo.isUnread()) {
            todo.setUnread(false);
            todo = repository.save(todo);
        }
        return withAttachments(todo);
    }

    /** Clear every unread finished-task notification ("mark all read"). */
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        repository.markAllRead();
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
            Story story = storyRepository.findById(storyId).orElse(null);
            // The story must exist — either as a persisted (possibly empty) Story
            // row or, for legacy chains, via its existing tagged todos.
            if (steps.isEmpty() && story == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Story not found: " + storyId);
            }
            String name = story != null ? story.getName() : "Story";
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
        return withAttachments(repository.save(todo));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found: " + id);
        }
        repository.deleteById(id);
        // The task's uploaded images go with it — nothing else references them.
        attachments.deleteForTodo(id);
        liveLog.clear(id);
    }

    /** Response for a single task, with its attachments loaded. */
    private TodoResponse withAttachments(Todo todo) {
        return TodoResponse.from(todo, attachments.viewsFor(todo.getId()));
    }

    private Todo find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found: " + id));
    }
}
