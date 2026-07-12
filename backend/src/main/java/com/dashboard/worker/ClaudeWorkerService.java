package com.dashboard.worker;

import com.dashboard.todo.Todo;
import com.dashboard.todo.TodoRepository;
import com.dashboard.todo.TodoStatus;
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
     * session that died with the previous process (its live log is gone). Put
     * it back in the queue so the worker re-attempts it.
     */
    @PostConstruct
    void requeueOrphans() {
        List<Todo> orphans = repository.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS);
        for (Todo t : orphans) {
            t.setStatus(TodoStatus.PENDING);
            repository.save(t);
        }
        if (!orphans.isEmpty()) {
            log.info("Requeued {} orphaned in-progress todo(s) after restart.", orphans.size());
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
            if (pending.isEmpty()) {
                return;
            }
            int limit = Math.min(props.getBatchSize(), pending.size());
            log.info("Worker picked up {} of {} pending todo(s).", limit, pending.size());
            for (int i = 0; i < limit; i++) {
                process(pending.get(i));
            }
        } finally {
            tickLock.unlock();
        }
    }

    private void process(Todo todo) {
        todo.setStatus(TodoStatus.IN_PROGRESS);
        todo.setProcessedAt(Instant.now());
        repository.save(todo);

        liveLog.start(todo.getId());
        liveLog.append(todo.getId(), "● Picked up todo #" + todo.getId() + ": " + todo.getTitle());
        log.info("Resolving todo #{}: {}", todo.getId(), todo.getTitle());

        try {
            ProcessResult r = runClaude(todo, buildPrompt(todo));
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

    private String buildPrompt(Todo todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous worker resolving a single todo item (id ")
                .append(todo.getId()).append(") from a dashboard.\n");
        sb.append("Do the work needed to complete it, then reply with a short summary of what you did ");
        sb.append("and any result or answer the user needs.\n");
        sb.append("If the task genuinely cannot be completed, end your reply with a line containing ")
                .append("exactly ").append(FAIL_SENTINEL).append(" followed by a brief reason.\n");
        sb.append("If this project has a CLAUDE.md, follow it — it explains how to make changes ")
                .append("to this project and how to file follow-up tasks.\n\n");
        sb.append("TODO TITLE: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("DETAILS:\n").append(todo.getDescription()).append('\n');
        }
        return sb.toString();
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
        Thread reader = new Thread(() -> streamOutput(todo.getId(), process, finalOutput), "claude-reader-" + todo.getId());
        reader.start();

        boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(5_000);
            return new ProcessResult(-1, finalOutput.toString(), true);
        }
        reader.join(5_000);
        return new ProcessResult(process.exitValue(), finalOutput.toString(), false);
    }

    /**
     * Reads the process output line by line. Each line is either a stream-json
     * event (rendered to a friendly log line, with the terminal {@code result}
     * event captured as the final answer) or plain text (streamed as-is).
     */
    private void streamOutput(Long todoId, Process process, StringBuilder finalOutput) {
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
                String rendered = renderEvent(event, finalOutput);
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
    private String renderEvent(JsonNode e, StringBuilder finalOutput) {
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
                String cost = e.has("total_cost_usd")
                        ? String.format(" · $%.4f", e.path("total_cost_usd").asDouble()) : "";
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

    private record ProcessResult(int exitCode, String finalOutput, boolean timedOut) {
    }
}
