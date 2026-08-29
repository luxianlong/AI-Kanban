package com.kanban.delegate;

import com.kanban.delegate.DelegatedTaskDtos.ClaimRequest;
import com.kanban.delegate.DelegatedTaskDtos.ClaimResponse;
import com.kanban.delegate.DelegatedTaskDtos.DelegatedTaskView;
import com.kanban.delegate.DelegatedTaskDtos.ReportRequest;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Public API for <em>delegated</em> ("代理") tasks — task #471.
 *
 * <p>A delegated task is one this server deliberately never resolves: the Claude
 * worker skips it (see {@code ClaudeWorkerService}), and instead it is published
 * here without any credential, so an outside worker — another bot, a colleague,
 * a cron script — can discover it, do the work wherever it lives, and report the
 * outcome back onto the board.
 *
 * <pre>
 *   # 1. find open work
 *   curl http://localhost:8080/api/public/tasks
 *
 *   # 2. (optional) claim it, so the board shows who took it and nobody else
 *   #    can overwrite your report. Keep the token it returns.
 *   curl -X POST http://localhost:8080/api/public/tasks/42/claim \
 *        -H 'Content-Type: application/json' -d '{"worker":"my-bot"}'
 *
 *   # 3. report the outcome
 *   curl -X POST http://localhost:8080/api/public/tasks/42/status \
 *        -H 'Content-Type: application/json' \
 *        -d '{"status":"RESOLVED","result":"done — here is the answer","token":"…"}'
 * </pre>
 *
 * <p>Everything under {@code /api/public/} is exempt from the site password (see
 * {@code frontend/docsGate.mjs}), so the endpoints must be safe for strangers.
 * They are, by construction: only tasks explicitly flagged {@code delegated} are
 * visible or writable — every other task 404s exactly as a non-existent id would,
 * so the ordinary board never leaks through this surface. Nothing here can create
 * or delete a task; the only mutation is a status/result report on work the owner
 * already chose to hand out.
 */
@RestController
@RequestMapping("/api/public/tasks")
@CrossOrigin(origins = "*")
public class DelegatedTaskController {

    private static final Logger log = LoggerFactory.getLogger(DelegatedTaskController.class);

    /** Default listing filter: work that is still worth picking up. */
    private static final String OPEN = "open";

    private final TodoRepository repository;
    private final SecureRandom random = new SecureRandom();

    public DelegatedTaskController(TodoRepository repository) {
        this.repository = repository;
    }

    /**
     * The public feed of delegated tasks, oldest first.
     *
     * <p>{@code ?status=} accepts {@code open} (the default — PENDING plus
     * IN_PROGRESS, i.e. anything not finished), {@code all}, or one exact
     * lifecycle state ({@code PENDING}, {@code IN_PROGRESS}, {@code RESOLVED},
     * {@code FAILED}). An outside worker normally polls the PENDING slice, which
     * is the unclaimed work.
     */
    @GetMapping
    public List<DelegatedTaskView> list(@RequestParam(required = false) String status) {
        String filter = status == null || status.isBlank() ? OPEN : status.trim().toLowerCase(Locale.ROOT);
        List<Todo> tasks;
        if (OPEN.equals(filter)) {
            tasks = repository.findByDelegatedTrueOrderByCreatedAtAsc().stream()
                    .filter(t -> t.getStatus() == TodoStatus.PENDING || t.getStatus() == TodoStatus.IN_PROGRESS)
                    .toList();
        } else if ("all".equals(filter)) {
            tasks = repository.findByDelegatedTrueOrderByCreatedAtAsc();
        } else {
            tasks = repository.findByDelegatedTrueAndStatusOrderByCreatedAtAsc(parseStatus(status));
        }
        return tasks.stream().map(DelegatedTaskView::from).toList();
    }

    /** One delegated task. A non-delegated (or missing) id is a 404. */
    @GetMapping("/{id}")
    public DelegatedTaskView get(@PathVariable Long id) {
        return DelegatedTaskView.from(find(id));
    }

    /**
     * Take ownership of an open delegated task: it moves to IN_PROGRESS, the
     * board shows the worker's name, and the caller gets a token that is then
     * required to report the outcome. Claiming is optional — an outside worker
     * that just wants to answer can post the report straight away — but it is
     * what stops two workers doing the same job, and what stops a third party
     * overwriting a claimer's answer. A task that is already claimed, or already
     * finished, responds 409.
     */
    @PostMapping("/{id}/claim")
    public ClaimResponse claim(@PathVariable Long id, @Valid @RequestBody(required = false) ClaimRequest req) {
        Todo todo = find(id);
        if (todo.getStatus() != TodoStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Delegated task #" + id + " is not open (status " + todo.getStatus() + ").");
        }
        if (todo.getDelegateToken() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Delegated task #" + id + " is already claimed"
                            + (todo.getDelegatedTo() == null ? "." : " by " + todo.getDelegatedTo() + "."));
        }
        String worker = req == null || req.worker() == null || req.worker().isBlank()
                ? null : req.worker().trim();
        String token = newToken();
        todo.setDelegatedTo(worker);
        todo.setDelegateClaimedAt(Instant.now());
        todo.setDelegateToken(token);
        todo.setStatus(TodoStatus.IN_PROGRESS);
        // Picked up by someone: it is running again, not a finished notification.
        todo.setUnread(false);
        Todo saved = repository.save(todo);
        log.info("Delegated task #{} claimed by {}.", id, worker == null ? "an anonymous worker" : worker);
        return new ClaimResponse(DelegatedTaskView.from(saved), token);
    }

    /**
     * Report a delegated task's outcome — the whole point of handing it out.
     * Moves it to the given status and records the result on the board, where it
     * shows up exactly like a task this server resolved itself (including the
     * unread badge when it lands in a terminal state).
     *
     * <p>Once the task has been claimed the matching token is required (403
     * otherwise); an unclaimed task accepts a report from anyone, so a worker can
     * skip the claim step for a quick answer.
     */
    @PostMapping("/{id}/status")
    public DelegatedTaskView report(@PathVariable Long id, @Valid @RequestBody ReportRequest req) {
        Todo todo = find(id);
        requireToken(todo, req.token());
        TodoStatus status = parseStatus(req.status());
        TodoStatus previous = todo.getStatus();
        boolean nowTerminal = status == TodoStatus.RESOLVED || status == TodoStatus.FAILED;
        // Same unread-notification bookkeeping as the board's own PATCH: a task
        // becomes an unread notification when it crosses into a terminal state,
        // and stops being one when it is handed back to the queue.
        if (nowTerminal && status != previous) {
            todo.setUnread(true);
        } else if (!nowTerminal) {
            todo.setUnread(false);
        }
        todo.setStatus(status);
        if (req.result() != null) {
            todo.setResult(req.result());
        }
        todo.setProcessedAt(Instant.now());
        if (status == TodoStatus.PENDING) {
            // Handed back: drop the claim so the next worker can take it.
            todo.setDelegateToken(null);
            todo.setDelegatedTo(null);
            todo.setDelegateClaimedAt(null);
        }
        Todo saved = repository.save(todo);
        log.info("Delegated task #{} reported as {} by an outside worker.", id, status);
        return DelegatedTaskView.from(saved);
    }

    /**
     * A claimed task may only be reported by whoever holds its token. Compared in
     * constant time so the endpoint can't be used to guess a token byte by byte.
     */
    private void requireToken(Todo todo, String presented) {
        String expected = todo.getDelegateToken();
        if (expected == null) {
            return; // unclaimed -> open to any finder
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = (presented == null ? "" : presented).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Delegated task #" + todo.getId() + " was claimed by another worker; "
                            + "reporting it needs the token returned by that claim.");
        }
    }

    private TodoStatus parseStatus(String raw) {
        try {
            return TodoStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + raw);
        }
    }

    /**
     * Look up a task through the public lens: only delegated ones exist here. A
     * normal task answers 404 rather than 403, so this endpoint can't be used to
     * probe which task ids the private board holds.
     */
    private Todo find(Long id) {
        return repository.findById(id)
                .filter(Todo::isDelegated)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No delegated task with id " + id + "."));
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
