package com.kanban.worker;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.kanban.work.SessionOutcome;
import com.kanban.work.TaskEligibility;
import com.kanban.work.TaskOutcomeService;
import com.kanban.work.TaskPromptBuilder;
import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Periodically drains the {@link TodoStatus#PENDING} queue by launching a
 * headless Claude Code session per item and recording whatever it produced.
 *
 * <p>Output is consumed <em>as it streams</em>: each event is rendered into a
 * human-readable line and appended to {@link LiveLogRegistry}, so the UI can
 * show live progress on an in-progress item. When Claude is run with
 * {@code --output-format stream-json} (the default), the terminal {@code result}
 * event carries the final answer, which is persisted on the todo. If a different
 * output format is configured, raw lines are streamed through unchanged.
 */
@Service
@EnableConfigurationProperties(WorkerProperties.class)
public class ClaudeWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerService.class);

    /** Agents print this line to mark a task as failed even when the CLI exits 0. */
    static final String FAIL_SENTINEL = ClaudeSessionRunner.FAIL_SENTINEL;

    private final TodoRepository repository;
    private final WorkerProperties props;
    private final LiveLogRegistry liveLog;
    private final RunningSessionRegistry sessions;
    private final ClaudeSessionRunner runner;
    private final TaskPromptBuilder prompts;
    private final TaskEligibility eligibility;
    private final TaskOutcomeService outcomes;
    private final ReentrantLock tickLock = new ReentrantLock();

    /**
     * Set once the Spring context starts closing.
     *
     * <p>A worker tick can sit inside {@link Process#waitFor} for up to
     * {@code worker.timeout-seconds} (30 minutes by default). That used to stall JVM
     * shutdown far past the restart script's kill grace, so every deploy fell back to
     * SIGKILL — and a SIGKILL landing in the middle of H2's shutdown compaction is what
     * truncates and corrupts kanban.mv.db. Aborting the session lets shutdown finish in
     * seconds, which keeps the restart on the clean SIGTERM path.
     */
    private volatile boolean shuttingDown = false;

    public ClaudeWorkerService(TodoRepository repository, WorkerProperties props,
                               LiveLogRegistry liveLog, RunningSessionRegistry sessions,
                               ClaudeSessionRunner runner, TaskPromptBuilder prompts,
                               TaskEligibility eligibility, TaskOutcomeService outcomes) {
        this.repository = repository;
        this.props = props;
        this.liveLog = liveLog;
        this.sessions = sessions;
        this.runner = runner;
        this.prompts = prompts;
        this.eligibility = eligibility;
        this.outcomes = outcomes;
    }

    /**
     * On startup, any todo left {@link TodoStatus#IN_PROGRESS} belongs to a
     * session that died with the previous process (its live log is gone).
     *
     * <p>Normally we return it to the queue so a crashed session is retried. But a
     * restart is frequently a <em>sibling</em> improvement task's guarded deploy
     * ({@code deploy.sh} schedules a detached restart moments later), which kills
     * whatever task happens to be running — including an unrelated one from another
     * story that was legitimately in-flight and may have already produced side
     * effects (committed files, filed steps). Blindly re-queuing that task
     * re-executes work it already did, and because a story of improvement tasks
     * keeps deploying, the same task can be re-run again and again. So a task that
     * has already been started {@link WorkerProperties#getMaxAttempts()} times is
     * routed to Needs Attention instead of re-queued.
     */
    @PostConstruct
    void requeueOrphans() {
        List<Todo> orphans = repository.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS);
        int requeued = 0;
        int abandoned = 0;
        for (Todo t : orphans) {
            if (t.isDelegated()) {
                // A delegated task is IN_PROGRESS because an OUTSIDE worker claimed
                // it — no session of ours died with the restart, and that worker is
                // very likely still on the job. Leave the claim alone.
                continue;
            }
            if (t.getAttempts() >= props.getMaxAttempts()) {
                fail(t, "Interrupted by a backend restart after " + t.getAttempts()
                        + " attempt(s) (the max-attempts cap). Not re-running automatically, "
                        + "because a restart mid-run is usually another task's deploy killing this "
                        + "one after it may have already done part of its work — re-running would "
                        + "duplicate it. Review the branch/output and re-queue manually if needed.");
                abandoned++;
            } else {
                t.setStatus(TodoStatus.PENDING);
                repository.save(t);
                requeued++;
            }
        }
        if (requeued > 0) {
            log.info("Requeued {} orphaned in-progress todo(s) after restart.", requeued);
        }
        if (abandoned > 0) {
            log.warn("Routed {} orphaned in-progress todo(s) to Needs Attention "
                    + "(hit the max-attempts cap of {}).", abandoned, props.getMaxAttempts());
        }
        // The async story classifier's hold doesn't survive a restart (the pass is
        // gone), so any task still flagged `classifying` would be stranded — the
        // worker skips it forever. Clear the flag so those tasks (already PENDING)
        // run on the next poll.
        List<Todo> held = repository.findByClassifyingTrue();
        for (Todo t : held) {
            t.setClassifying(false);
            repository.save(t);
        }
        if (!held.isEmpty()) {
            log.info("Cleared {} stale classify hold(s) after restart.", held.size());
        }
    }

    /**
     * Abort any in-flight Claude session so the JVM can shut down promptly.
     *
     * <p>The interrupted todos are deliberately left {@link TodoStatus#IN_PROGRESS}:
     * {@link #requeueOrphans()} puts them back on the queue at the next start, honouring
     * the max-attempts cap. That is already the right recovery path for a run killed by
     * a restart, so shutdown reuses it rather than inventing a new terminal state.
     */
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        int aborted = sessions.abortAll();
        if (aborted > 0) {
            log.info("Shutting down: aborted {} running Claude session(s); their todos stay "
                    + "IN_PROGRESS and are requeued on the next start.", aborted);
        }
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:15000}",
            initialDelayString = "${worker.initial-delay-ms:10000}")
    public void poll() {
        if (!props.isEnabled() || shuttingDown) {
            return;
        }
        if (!tickLock.tryLock()) {
            log.debug("Previous worker tick still running; skipping.");
            return;
        }
        try {
            List<Todo> pending = repository.findByStatusOrderByCreatedAtAsc(TodoStatus.PENDING);
            List<Todo> eligible = pending.stream().filter(this::isEligible).toList();
            if (eligible.isEmpty()) {
                return;
            }
            int limit = Math.min(props.getBatchSize(), eligible.size());
            log.info("Worker picked up {} of {} eligible pending todo(s).", limit, eligible.size());
            for (int i = 0; i < limit; i++) {
                process(eligible.get(i));
            }
        } finally {
            tickLock.unlock();
        }
    }

    private void process(Todo todo) {
        todo.setStatus(TodoStatus.IN_PROGRESS);
        todo.setProcessedAt(Instant.now());
        // Count this pickup so a task orphaned by a restart can't be re-run forever
        // (see requeueOrphans): past the cap it's routed to Needs Attention.
        todo.setAttempts(todo.getAttempts() + 1);
        repository.save(todo);

        liveLog.start(todo.getId());
        liveLog.append(todo.getId(), "● Picked up todo #" + todo.getId() + ": " + todo.getTitle());
        log.info("Resolving todo #{}: {}", todo.getId(), todo.getTitle());

        try {
            ProcessResult r = runner.run(LiveLogRegistry.todoKey(todo.getId()), buildPrompt(todo));
            if (shuttingDown) {
                // Shutdown killed this session — it neither failed nor timed out, so don't
                // record a terminal status. Leaving it IN_PROGRESS hands it to
                // requeueOrphans() on the next start.
                sessions.consumeStopRequested(todo.getId());
                liveLog.append(todo.getId(),
                        "■ Backend shutting down — this task will be requeued on restart.");
                log.info("Shutdown interrupted todo #{}; left IN_PROGRESS for requeue.", todo.getId());
                return;
            }
            // What the run means is TaskOutcomeService's call, not this class's: a remote
            // worker reports the same facts over the agent API and must be read the same way.
            SessionOutcome outcome = new SessionOutcome(r.exitCode(), r.timedOut(),
                    r.abandonedBackgroundWork(), sessions.consumeStopRequested(todo.getId()),
                    r.finalOutput(), r.costUsd(), r.durationMs());
            TaskOutcomeService.Applied applied = outcomes.apply(todo, outcome,
                    props.getTimeoutSeconds(), props.getBackgroundWaitSeconds());
            liveLog.append(todo.getId(), applied.logLine());
        } catch (Exception e) {
            log.error("Worker error on todo #{}", todo.getId(), e);
            liveLog.append(todo.getId(), "✗ Worker error: " + e.getMessage());
            fail(todo, "Worker error: " + e.getMessage());
        }
    }

    private void fail(Todo todo, String message) {
        todo.setStatus(TodoStatus.FAILED);
        todo.setResult(message);
        repository.save(todo);
        log.warn("Failed todo #{}: {}", todo.getId(), message.lines().findFirst().orElse(""));
    }

    /**
     * Whether a worker may pick a pending task up on this tick — see
     * {@link TaskEligibility#isEligible}. Delegated rather than duplicated so the agent API
     * that hands work to remote workers applies exactly the same rule; two answers to "may
     * this run?" would let a remote worker start a story's third step while its second was
     * still going.
     */
    boolean isEligible(Todo todo) {
        return eligibility.isEligible(todo);
    }

    /**
     * The prompt for one task — see {@link TaskPromptBuilder}. It lives there because a
     * remote worker cannot build it: the story context is assembled from other rows in the
     * database, and a worker has none.
     */
    String buildPrompt(Todo todo) {
        return prompts.buildPrompt(todo);
    }
}
