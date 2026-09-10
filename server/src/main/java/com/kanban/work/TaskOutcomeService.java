package com.kanban.work;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns what a session produced into what the board says about it.
 *
 * <p>The judgement lives here, on the server, and nowhere else. A worker reports facts —
 * exit code, whether it timed out, what the session finally said — and this decides what
 * they mean. That split is deliberate: the rules below are not obvious (a clean exit code
 * means nothing if the CLI killed the session's background work; a sentinel line in the
 * output means failure despite everything looking fine), and a worker jar on someone else's
 * laptop is the one part of the system a deploy cannot update. If the reading of a result
 * lived in the worker, an old worker would quietly disagree with the board about what
 * happened, and the board would be the one that was wrong.
 */
@Service
public class TaskOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(TaskOutcomeService.class);

    /** Agents print this to mark their work failed even when the CLI exits 0. */
    public static final String FAIL_SENTINEL = TaskPromptBuilder.FAIL_SENTINEL;

    private final TodoRepository repository;

    public TaskOutcomeService(TodoRepository repository) {
        this.repository = repository;
    }

    /**
     * What was decided, so the caller can put the same words in the live log.
     *
     * @param logLine the one line worth showing on the card's transcript
     */
    public record Applied(TodoStatus status, String logLine) {
    }

    /**
     * Record the outcome of one task's session and return what to say about it.
     *
     * <p>The order of these branches matters and is not arbitrary — see each comment. The
     * timeouts are passed in rather than read from configuration because a remote worker ran
     * under the limits its work unit carried, which are not necessarily this server's current
     * defaults; the message has to name the clock the session actually ran on.
     */
    public Applied apply(Todo todo, SessionOutcome outcome, long timeoutSeconds,
                         long backgroundWaitSeconds) {
        // Record what the run consumed whether it resolved or failed, so the card can show
        // what it cost either way.
        if (outcome.costUsd() != null) {
            todo.setCostUsd(outcome.costUsd());
        }
        if (outcome.durationMs() != null) {
            todo.setDurationMs(outcome.durationMs());
        }

        String output = outcome.outputOrEmpty();

        if (outcome.stopped()) {
            // The user asked to stop this session: the process was killed
            // deliberately, so don't dress it up as a timeout or crash.
            return fail(todo, "✗ Stopped by user request.",
                    "Stopped by user request. The running session was terminated "
                            + "before it finished; requeue the task to run it again.");
        }
        if (outcome.timedOut()) {
            return fail(todo, "✗ Timed out after " + timeoutSeconds + "s.",
                    "Timed out after " + timeoutSeconds + "s.");
        }
        if (outcome.exitCode() != 0) {
            return fail(todo, "✗ Claude exited with code " + outcome.exitCode() + ".",
                    "Claude exited with code " + outcome.exitCode() + ".\n\n" + output);
        }
        if (outcome.abandonedBackgroundWork()) {
            // Same failure the workflow runner guards against: the session handed its
            // work to a dynamic workflow or a background sub-session, the CLI ran out
            // of patience and killed it, and what the session said before that is a
            // promise to report back. Sending that to Done would file a stand-in as
            // the task's answer.
            return fail(todo, "✗ Background work was still running and was terminated.",
                    "The session ended while background work it had started was still"
                            + " running, so the CLI terminated that work after "
                            + backgroundWaitSeconds + "s. What the session replied was"
                            + " written before then and describes results it never received:\n\n"
                            + output);
        }
        if (output.contains(FAIL_SENTINEL)) {
            // The agent signalled semantic failure (e.g. a guarded deploy whose
            // tests failed) even though the CLI exited cleanly.
            return fail(todo, "✗ Task reported failure.", output);
        }

        todo.setStatus(TodoStatus.RESOLVED);
        todo.setResult(output.isBlank() ? "(no output)" : output);
        repository.save(todo);
        log.info("Resolved todo #{}.", todo.getId());
        return new Applied(TodoStatus.RESOLVED, "✔ Done.");
    }

    private Applied fail(Todo todo, String logLine, String message) {
        todo.setStatus(TodoStatus.FAILED);
        todo.setResult(message);
        repository.save(todo);
        log.warn("Failed todo #{}: {}", todo.getId(), message.lines().findFirst().orElse(""));
        return new Applied(TodoStatus.FAILED, logLine);
    }

    // NOTE: this deliberately does NOT set todo.unread, because the in-process worker never
    // did either — only the manual PATCH and the delegated-task report do. Todo.unread's own
    // documentation says a task is flagged "the moment it crosses into a terminal state",
    // which suggests the worker path has always been missing it and the badge stays dark for
    // exactly the tasks that finish on their own. That looks like a bug, but fixing it here
    // would change board behaviour inside a refactor that is meant to change none, so it is
    // left alone and called out instead.
}
