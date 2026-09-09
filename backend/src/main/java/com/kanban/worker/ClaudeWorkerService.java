package com.kanban.worker;

import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
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
    private final AttachmentService attachments;
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
                               ClaudeSessionRunner runner, AttachmentService attachments) {
        this.repository = repository;
        this.props = props;
        this.liveLog = liveLog;
        this.sessions = sessions;
        this.runner = runner;
        this.attachments = attachments;
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

    /**
     * Whether the worker may pick a pending task up on this tick.
     *
     * <ul>
     *   <li>A <em>delegated</em> (代理) task is never ours to run at all: it waits
     *       in the queue until an outside worker finds it through the public API
     *       ({@code /api/public/tasks}) and reports the outcome back.
     *   <li>A task still held by the async story classifier waits until that pass
     *       clears the hold, so it can't run before it has been filed.
     *   <li>Story steps run in order: a step waits until every earlier step in its
     *       chain has RESOLVED, so a story never runs two tasks at once and halts
     *       if one of its steps fails.
     * </ul>
     */
    boolean isEligible(Todo todo) {
        return !todo.isDelegated() && !todo.isClassifying() && !isBlockedByStory(todo);
    }

    /**
     * A story step is blocked while any earlier step in the same chain is not yet
     * RESOLVED (still pending, running, or failed). Standalone todos are never
     * blocked. Keeping blocked steps in PENDING means they flow automatically the
     * moment their predecessor finishes — no extra bookkeeping needed.
     */
    private boolean isBlockedByStory(Todo todo) {
        String storyId = todo.getStoryId();
        if (storyId == null || todo.getStoryOrder() == null) {
            return false;
        }
        int order = todo.getStoryOrder();
        for (Todo step : repository.findByStoryIdOrderByStoryOrderAsc(storyId)) {
            if (step.getStoryOrder() != null
                    && step.getStoryOrder() < order
                    && step.getStatus() != TodoStatus.RESOLVED) {
                return true;
            }
        }
        return false;
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
            // Record whatever the session reported about cost/runtime, whether it
            // resolved or failed, so the card can show what the run consumed.
            if (r.costUsd() != null) {
                todo.setCostUsd(r.costUsd());
            }
            if (r.durationMs() != null) {
                todo.setDurationMs(r.durationMs());
            }
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
            if (sessions.consumeStopRequested(todo.getId())) {
                // The user asked to stop this session: the process was killed
                // deliberately, so don't dress it up as a timeout or crash.
                liveLog.append(todo.getId(), "✗ Stopped by user request.");
                fail(todo, "Stopped by user request. The running session was terminated "
                        + "before it finished; requeue the task to run it again.");
            } else if (r.timedOut()) {
                liveLog.append(todo.getId(), "✗ Timed out after " + props.getTimeoutSeconds() + "s.");
                fail(todo, "Timed out after " + props.getTimeoutSeconds() + "s.");
            } else if (r.exitCode() != 0) {
                liveLog.append(todo.getId(), "✗ Claude exited with code " + r.exitCode() + ".");
                fail(todo, "Claude exited with code " + r.exitCode() + ".\n\n" + r.finalOutput());
            } else if (r.finalOutput().contains(FAIL_SENTINEL)) {
                // The agent signalled semantic failure (e.g. a guarded deploy whose
                // tests failed) even though the CLI exited cleanly.
                liveLog.append(todo.getId(), "✗ Task reported failure.");
                fail(todo, r.finalOutput());
            } else {
                String output = r.finalOutput().isBlank() ? "(no output)" : r.finalOutput();
                todo.setStatus(TodoStatus.RESOLVED);
                todo.setResult(output);
                repository.save(todo);
                liveLog.append(todo.getId(), "✔ Done.");
                log.info("Resolved todo #{}.", todo.getId());
            }
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

    String buildPrompt(Todo todo) {
        if (todo.isStoryPlanner()) {
            return buildPlannerPrompt(todo);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous worker resolving a single todo item (id ")
                .append(todo.getId()).append(") from a Kanban board.\n");
        sb.append("Do the work needed to complete it, then reply with a short summary of what you did ");
        sb.append("and any result or answer the user needs.\n");
        sb.append("If the task genuinely cannot be completed, end your reply with a line containing ")
                .append("exactly ").append(FAIL_SENTINEL).append(" followed by a brief reason.\n");
        if (todo.isProjectImprovement()) {
            sb.append("If this project has a CLAUDE.md, follow it — it explains how to make changes ")
                    .append("to this project and how to file follow-up tasks.\n\n");
        } else {
            // General task: it is NOT about improving this Kanban app itself. It MAY
            // create/change the RESOURCES it produces — result pages, docs, data files,
            // images — so the work is persisted and reachable. No example directory is
            // named: naming one made it the only filesystem landmark a fresh session had,
            // and tasks piled their output there whether it belonged or not. But it must
            // NOT change the Kanban's OWN CODE (backend, frontend source, build scripts,
            // config), and it must NOT deploy or restart the running website. Only
            // project-improvement tasks change app code and go through the guarded
            // deploy (which restarts the backend); doing so for a general task is
            // needless churn.
            sb.append("This is a GENERAL task — it is NOT about improving this Kanban project itself. ")
                    .append("You MAY create or change RESOURCE/output files that this task produces — for ")
                    .append("example result pages, documents, data files or images — so your work is ")
                    .append("saved and reachable. ")
                    .append("But do NOT modify the Kanban application's OWN CODE (its backend, frontend source, ")
                    .append("build scripts or config), do NOT run scripts/deploy.sh, and do NOT rebuild or ")
                    .append("restart the website or its backend. There is no need to redeploy the app for this ")
                    .append("task. Just do the work the task asks for (using any tools/skills as needed) and ")
                    .append("reply with the result. You may read files, including CLAUDE.md, for context.\n\n");
        }
        appendStoryContext(sb, todo);
        sb.append("TODO TITLE: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("DETAILS:\n").append(todo.getDescription()).append('\n');
        }
        appendAttachments(sb, todo);
        return sb.toString();
    }

    /**
     * If the user attached images to the task, point the agent at the real files.
     * They are stored on this machine by the backend, so the session can open them
     * directly with its Read tool — the picture is part of the task's input, not
     * just decoration on the card.
     */
    private void appendAttachments(StringBuilder sb, Todo todo) {
        if (attachments == null || todo.getId() == null) {
            return;
        }
        List<Attachment> files = attachments.listFor(todo.getId());
        if (files.isEmpty()) {
            return;
        }
        sb.append("\nATTACHED IMAGES (").append(files.size())
                .append("): the user attached the following image file(s) to this task. ")
                .append("They are saved on this machine — open each one with the Read tool ")
                .append("(it renders images) before you start, and treat what they show as part ")
                .append("of the task's instructions:\n");
        for (Attachment a : files) {
            sb.append("  - ").append(attachments.pathOf(a).toString())
                    .append("  (original name: ").append(a.getFileName()).append(")\n");
        }
    }

    /** Base URL the worker's agent uses to call back into this API. */
    private static final String API_BASE = "http://localhost:8080";

    /**
     * Prompt for a story planner (see {@link Todo#isStoryPlanner()}). The agent's
     * job is NOT to do the work but to break the goal into concrete step tasks and
     * file each one under the story via the steps endpoint. The steps are queued
     * (PENDING); the worker runs them one at a time, in order.
     */
    private String buildPlannerPrompt(Todo todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are planning a STORY on a Kanban board — a high-level goal that must be ")
                .append("broken down into concrete, independently-workable tasks. Your job is to PLAN, ")
                .append("not to implement: do NOT write code, change files, or resolve the goal yourself.\n\n");
        sb.append("Do this:\n");
        sb.append("1. Consider the goal below. If this project has a CLAUDE.md or the goal is about this ")
                .append("repo, you may read files to understand it — but make no changes.\n");
        sb.append("2. Break the goal into an ordered list of concrete steps (usually 2–6). Each step ")
                .append("should be a single, clearly-scoped task another agent can pick up and finish.\n");
        sb.append("3. Create each step, in order, by POSTing to ")
                .append(API_BASE).append("/api/stories/").append(todo.getStoryId()).append("/steps with a JSON body ")
                .append("{\"title\":\"…\",\"description\":\"…\"} (title ≤200 chars; description = what to do and why). ")
                .append("For example:\n")
                .append("   curl -s -X POST ").append(API_BASE).append("/api/stories/").append(todo.getStoryId())
                .append("/steps -H 'Content-Type: application/json' -d '{\"title\":\"…\",\"description\":\"…\"}'\n");
        sb.append("4. The steps are queued and run automatically in the order you create them (each waits ")
                .append("for the previous step to finish). Do not POST to /api/todos and do not create the ")
                .append("story itself — only add steps to THIS story.\n");
        sb.append("5. Reply with a short summary listing the steps you created.\n");
        sb.append("If the goal cannot be decomposed, end your reply with a line containing exactly ")
                .append(FAIL_SENTINEL).append(" followed by a brief reason.\n\n");
        sb.append("STORY GOAL: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("CONTEXT:\n").append(todo.getDescription()).append('\n');
        }
        return sb.toString();
    }

    /** Max chars of a prior step's result to carry into the next step's prompt. */
    private static final int STORY_RESULT_BUDGET = 4000;

    /**
     * If the todo is a step in a story, prepend the chain's context: which step
     * this is, and what the earlier steps produced. This is what makes a story a
     * <em>chain</em> — each step builds on the results of the ones before it.
     */
    private void appendStoryContext(StringBuilder sb, Todo todo) {
        if (todo.getStoryId() == null || todo.getStoryOrder() == null) {
            return;
        }
        List<Todo> steps = repository.findByStoryIdOrderByStoryOrderAsc(todo.getStoryId());
        int order = todo.getStoryOrder();
        // Count real steps only — the story's planner seed carries a null order.
        long total = steps.stream().filter(s -> !s.isStoryPlanner()).count();
        sb.append("This task is step ").append(order + 1).append(" of ").append(total)
                .append(" in the story \"").append(todo.getStoryName()).append("\". ")
                .append("The steps run in order and build on each other; do your part so the ")
                .append("next step can continue.\n");
        boolean any = false;
        for (Todo step : steps) {
            if (step.getStoryOrder() == null || step.getStoryOrder() >= order) {
                continue;
            }
            if (!any) {
                sb.append("\nResults of the earlier steps (use them as context):\n");
                any = true;
            }
            sb.append("\n— Step ").append(step.getStoryOrder() + 1)
                    .append(" \"").append(step.getTitle()).append("\":\n");
            String result = step.getResult();
            if (result == null || result.isBlank()) {
                sb.append("(no result recorded)\n");
            } else {
                sb.append(result.length() > STORY_RESULT_BUDGET
                        ? result.substring(0, STORY_RESULT_BUDGET) + "\n…(truncated)\n"
                        : result + "\n");
            }
        }
        sb.append('\n');
    }

}
