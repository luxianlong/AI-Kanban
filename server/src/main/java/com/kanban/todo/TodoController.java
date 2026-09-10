package com.kanban.todo;

import com.kanban.attachment.AttachmentDtos.AttachmentView;
import com.kanban.attachment.AttachmentService;
import com.kanban.agent.AgentService;
import com.kanban.protocol.WorkKind;
import com.kanban.todo.TodoDtos.CreateTodoRequest;
import com.kanban.todo.TodoDtos.RerunTodoRequest;
import com.kanban.todo.TodoDtos.SetStoryRequest;
import com.kanban.todo.TodoDtos.TodoResponse;
import com.kanban.todo.TodoDtos.UpdateStatusRequest;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.SessionLogService;
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
    private final SessionLogService sessionLogs;
    private final StoryClassifier classifier;
    private final AiStoryClassifier aiClassifier;
    private final AttachmentService attachments;
    /** How Stop reaches a session running on another machine — see {@link #stop}. */
    private final AgentService agent;

    public TodoController(TodoRepository repository, StoryRepository storyRepository,
                          LiveLogRegistry liveLog, SessionLogService sessionLogs,
                          StoryClassifier classifier, AiStoryClassifier aiClassifier,
                          AttachmentService attachments, AgentService agent) {
        this.repository = repository;
        this.storyRepository = storyRepository;
        this.liveLog = liveLog;
        this.sessionLogs = sessionLogs;
        this.classifier = classifier;
        this.aiClassifier = aiClassifier;
        this.attachments = attachments;
        this.agent = agent;
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
     * Session transcript for a todo: the running log while it is IN_PROGRESS, and the
     * transcript that was kept once it finished.
     *
     * <p>{@code archived} says which of the two this is. It matters to a reader: the same
     * text under "live" means the session is still writing, and under "saved" means this is
     * the whole of it. It is also the only signal that a finished task has no transcript
     * because it ran before the server last restarted, rather than because it did nothing.
     */
    @GetMapping("/{id}/log")
    public Map<String, Object> log(@PathVariable Long id) {
        Todo todo = find(id);
        return Map.of(
                "id", todo.getId(),
                "status", todo.getStatus(),
                "archived", sessionLogs.isArchived(todo),
                "log", sessionLogs.read(todo));
    }

    /**
     * Stop the running Claude session for an in-progress task, on user request.
     *
     * <p>The server cannot reach the session — it belongs to a worker that may be on a laptop
     * behind NAT — so the request is written onto the task's lease and the worker acts on it at
     * its next heartbeat. Stopping is therefore as prompt as the heartbeat interval and no
     * more. The task then lands in Needs Attention, from where it can be requeued.
     *
     * <p>Only valid while the task is IN_PROGRESS with something actually running — otherwise
     * there is nothing to stop (409).
     */
    @PostMapping("/{id}/stop")
    public TodoResponse stop(@PathVariable Long id) {
        Todo todo = find(id);
        if (todo.getStatus() != TodoStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task #" + id + " is not running (status " + todo.getStatus() + ").");
        }
        if (!agent.requestStop(WorkKind.TASK, id)) {
            // IN_PROGRESS but no active lease: the worker holding it has already lost or
            // released the claim, so there is nothing left to ask.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No live session found for task #" + id + " to stop.");
        }
        liveLog.append(id, "● Stop requested by user — asking the worker to terminate the "
                + "session on its next check-in…");
        // The task flips to FAILED once the killed session is reported; return the current
        // (still IN_PROGRESS) row and let the UI poll it through.
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
