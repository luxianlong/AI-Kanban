package com.kanban.worker;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    static final String FAIL_SENTINEL = "[[TASK_FAILED]]";

    private final TodoRepository repository;
    private final WorkerProperties props;
    private final LiveLogRegistry liveLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock tickLock = new ReentrantLock();

    public ClaudeWorkerService(TodoRepository repository, WorkerProperties props, LiveLogRegistry liveLog) {
        this.repository = repository;
        this.props = props;
        this.liveLog = liveLog;
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

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:15000}",
            initialDelayString = "${worker.initial-delay-ms:10000}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        if (!tickLock.tryLock()) {
            log.debug("Previous worker tick still running; skipping.");
            return;
        }
        try {
            List<Todo> pending = repository.findByStatusOrderByCreatedAtAsc(TodoStatus.PENDING);
            // Story steps run in order: a step is skipped until every earlier step
            // in its chain has RESOLVED, so a story never runs two tasks at once
            // and halts if one of its steps fails. A task still held by the async
            // story classifier is skipped until that pass clears the hold.
            List<Todo> eligible = pending.stream()
                    .filter(t -> !t.isClassifying() && !isBlockedByStory(t))
                    .toList();
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
            ProcessResult r = runClaude(todo, buildPrompt(todo));
            // Record whatever the session reported about cost/runtime, whether it
            // resolved or failed, so the card can show what the run consumed.
            if (r.costUsd() != null) {
                todo.setCostUsd(r.costUsd());
            }
            if (r.durationMs() != null) {
                todo.setDurationMs(r.durationMs());
            }
            if (r.timedOut()) {
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
            // create/change the RESOURCES it produces — result pages, docs, research
            // artifacts, data files, images (e.g. under docs/, research/,
            // frontend/public/) — so the work is persisted and reachable. But it must
            // NOT change the Kanban's OWN CODE (backend, frontend source, build scripts,
            // config), and it must NOT deploy or restart the running website. Only
            // project-improvement tasks change app code and go through the guarded
            // deploy (which restarts the backend); doing so for a general task is
            // needless churn.
            sb.append("This is a GENERAL task — it is NOT about improving this Kanban project itself. ")
                    .append("You MAY create or change RESOURCE/output files that this task produces — for ")
                    .append("example result pages, documents, research artifacts, data files or images (e.g. ")
                    .append("under docs/, research/ or frontend/public/) — so your work is saved and reachable. ")
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
        return sb.toString();
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

    private ProcessResult runClaude(Todo todo, String prompt) throws IOException, InterruptedException {
        File workDir = new File(props.getWorkingDirectory());
        if (!workDir.exists() && !workDir.mkdirs()) {
            log.warn("Could not create working directory {}", workDir);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(props.getClaudeCommand());
        cmd.addAll(props.getExtraArgs());
        cmd.add("-p");
        cmd.add(prompt);

        Process process = new ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();

        // Drain stdout on a separate thread so we can enforce the timeout even if
        // the process wedges without closing its stream.
        StringBuilder finalOutput = new StringBuilder();
        RunMetrics metrics = new RunMetrics();
        Thread reader = new Thread(() -> streamOutput(todo.getId(), process, finalOutput, metrics),
                "claude-reader-" + todo.getId());
        reader.start();

        boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(5_000);
            return new ProcessResult(-1, finalOutput.toString(), true, metrics.costUsd, metrics.durationMs);
        }
        reader.join(5_000);
        return new ProcessResult(process.exitValue(), finalOutput.toString(), false, metrics.costUsd, metrics.durationMs);
    }

    /**
     * Reads the process output line by line. Each line is either a stream-json
     * event (rendered to a friendly log line, with the terminal {@code result}
     * event captured as the final answer) or plain text (streamed as-is).
     */
    private void streamOutput(Long todoId, Process process, StringBuilder finalOutput, RunMetrics metrics) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = tryParse(line);
                if (event == null) {
                    // Not JSON (e.g. text/plain output format) — stream verbatim.
                    liveLog.append(todoId, line);
                    finalOutput.append(line).append('\n');
                    continue;
                }
                String rendered = renderEvent(event, finalOutput, metrics);
                if (rendered != null && !rendered.isBlank()) {
                    liveLog.append(todoId, rendered);
                }
            }
        } catch (IOException e) {
            liveLog.append(todoId, "✗ Error reading session output: " + e.getMessage());
        }
    }

    private JsonNode tryParse(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            return node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Turns one stream-json event into a readable log line. Captures the final
     * answer from the terminal {@code result} event into {@code finalOutput}.
     * Returns {@code null} to skip an event that has nothing worth showing.
     */
    private String renderEvent(JsonNode e, StringBuilder finalOutput, RunMetrics metrics) {
        String type = e.path("type").asText("");
        switch (type) {
            case "system" -> {
                if ("init".equals(e.path("subtype").asText())) {
                    return "● Session started · model " + e.path("model").asText("?");
                }
                return null;
            }
            case "assistant" -> {
                StringBuilder out = new StringBuilder();
                for (JsonNode part : e.path("message").path("content")) {
                    String pt = part.path("type").asText();
                    if ("text".equals(pt)) {
                        String text = part.path("text").asText("").strip();
                        if (!text.isBlank()) {
                            out.append("🤖 ").append(text).append('\n');
                        }
                    } else if ("tool_use".equals(pt)) {
                        out.append("🔧 ").append(part.path("name").asText("tool"))
                                .append("  ").append(summarizeInput(part.path("input"))).append('\n');
                    }
                }
                String s = out.toString().strip();
                return s.isBlank() ? null : s;
            }
            case "user" -> {
                for (JsonNode part : e.path("message").path("content")) {
                    if ("tool_result".equals(part.path("type").asText())) {
                        boolean err = part.path("is_error").asBoolean(false);
                        return (err ? "   ↳ ✗ " : "   ↳ ✓ ") + firstLine(toolResultText(part), 160);
                    }
                }
                return null;
            }
            case "result" -> {
                String result = e.path("result").asText("");
                if (!result.isBlank()) {
                    finalOutput.setLength(0);
                    finalOutput.append(result);
                }
                if (e.has("total_cost_usd")) {
                    metrics.costUsd = e.path("total_cost_usd").asDouble();
                }
                if (e.has("duration_ms")) {
                    metrics.durationMs = e.path("duration_ms").asLong();
                }
                String cost = metrics.costUsd != null
                        ? String.format(" · $%.4f", metrics.costUsd) : "";
                double secs = e.path("duration_ms").asLong(0) / 1000.0;
                return String.format("✔ Completed in %.1fs%s", secs, cost);
            }
            default -> {
                return null;
            }
        }
    }

    private String summarizeInput(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        // Common single-field tools read better unwrapped.
        for (String key : new String[]{"command", "file_path", "path", "pattern", "prompt", "url", "query"}) {
            if (input.hasNonNull(key)) {
                return firstLine(input.get(key).asText(), 160);
            }
        }
        return firstLine(input.toString(), 160);
    }

    private String toolResultText(JsonNode part) {
        JsonNode content = part.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : content) {
                if (c.hasNonNull("text")) {
                    sb.append(c.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String firstLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    private record ProcessResult(int exitCode, String finalOutput, boolean timedOut,
                                 Double costUsd, Long durationMs) {
    }

    /**
     * Mutable holder for run metrics captured off the terminal {@code result}
     * event by the streaming reader thread and read back once it finishes.
     */
    private static final class RunMetrics {
        Double costUsd;
        Long durationMs;
    }
}
