package com.kanban.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one headless Claude Code session and reports what it produced.
 *
 * <p>This is the single place that knows how to launch the CLI, enforce the
 * timeout, and turn {@code --output-format stream-json} events into readable log
 * lines. Both things that run sessions use it — {@link ClaudeWorkerService} for a
 * board task and {@code WorkflowRunnerService} for one node of a workflow — so the
 * live log, the stop button and the shutdown abort behave identically for both.
 *
 * <p>Each call is a <em>fresh</em> session: nothing is resumed and no conversation
 * state is shared between runs. Whatever context a run needs must be written into
 * its prompt by the caller.
 */
@Service
@EnableConfigurationProperties(WorkerProperties.class)
public class ClaudeSessionRunner {

    /**
     * Agents print this line to mark their work as failed even when the CLI exits 0
     * (e.g. a guarded deploy whose tests failed). Every caller checks the final
     * output for it, so it lives with the runner rather than in one of them.
     */
    public static final String FAIL_SENTINEL = "[[TASK_FAILED]]";

    private final WorkerProperties props;
    private final LiveLogRegistry liveLog;
    private final RunningSessionRegistry sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeSessionRunner(WorkerProperties props, LiveLogRegistry liveLog,
                               RunningSessionRegistry sessions) {
        this.props = props;
        this.liveLog = liveLog;
        this.sessions = sessions;
    }

    /** What one finished session produced. */
    public record ProcessResult(int exitCode, String finalOutput, boolean timedOut,
                                Double costUsd, Long durationMs) {
    }

    /**
     * Launch {@code claude -p <prompt>} in {@code worker.working-directory} and block
     * until it finishes or hits {@code worker.timeout-seconds}.
     *
     * @param sessionKey the key this run logs under and can be stopped by — see
     *                   {@link LiveLogRegistry#todoKey}/{@link LiveLogRegistry#nodeKey}
     */
    public ProcessResult run(String sessionKey, String prompt) throws IOException, InterruptedException {
        return run(sessionKey, prompt, new File(props.getWorkingDirectory()));
    }

    /**
     * As {@link #run(String, String)}, but in a caller-chosen directory. The workflow
     * runner uses this to keep its nodes out of the repo: a node transforms text and has
     * no business writing into the working tree, so it gets a scratch directory instead
     * of the shared {@code worker.working-directory}.
     */
    public ProcessResult run(String sessionKey, String prompt, File workDir)
            throws IOException, InterruptedException {
        return run(sessionKey, prompt, workDir, List.of());
    }

    /**
     * As {@link #run(String, String, File)}, but with extra CLI args for this one
     * session, appended after {@code worker.extra-args}.
     *
     * <p>This is for a difference that belongs to a single run rather than to the
     * worker — the workflow runner uses it to start an entry node in ultracode mode
     * (see {@code WorkflowRunnerService#sessionArgsFor}). Configuration that applies to
     * <em>every</em> session belongs in {@code worker.extra-args} instead.
     */
    public ProcessResult run(String sessionKey, String prompt, File workDir, List<String> sessionArgs)
            throws IOException, InterruptedException {
        if (!workDir.exists() && !workDir.mkdirs()) {
            liveLog.append(sessionKey, "! Could not create working directory " + workDir);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(props.getClaudeCommand());
        cmd.addAll(props.getExtraArgs());
        cmd.addAll(sessionArgs);
        cmd.add("-p");
        cmd.add(prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true);
        // Worker sessions are one-shot task runners, not a continuing conversation with
        // the user: nothing they learn should be written to (or read from) the CLI's
        // auto-memory store, which is shared with the interactive sessions run by hand.
        pb.environment().put("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");
        Process process = pb.start();
        process.getOutputStream().close();
        // Expose the live process so a user can stop this session on demand; always
        // drop it again once the run ends, however it ends.
        sessions.register(sessionKey, process);

        try {
            // Drain stdout on a separate thread so we can enforce the timeout even if
            // the process wedges without closing its stream.
            StringBuilder finalOutput = new StringBuilder();
            RunMetrics metrics = new RunMetrics();
            Thread reader = new Thread(() -> streamOutput(sessionKey, process, finalOutput, metrics),
                    "claude-reader-" + sessionKey);
            reader.start();

            boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                reader.join(5_000);
                return new ProcessResult(-1, finalOutput.toString(), true, metrics.costUsd, metrics.durationMs);
            }
            reader.join(5_000);
            return new ProcessResult(process.exitValue(), finalOutput.toString(), false,
                    metrics.costUsd, metrics.durationMs);
        } finally {
            sessions.unregister(sessionKey);
        }
    }

    /**
     * Reads the process output line by line. Each line is either a stream-json
     * event (rendered to a friendly log line, with the terminal {@code result}
     * event captured as the final answer) or plain text (streamed as-is).
     */
    private void streamOutput(String sessionKey, Process process, StringBuilder finalOutput,
                              RunMetrics metrics) {
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
                    liveLog.append(sessionKey, line);
                    finalOutput.append(line).append('\n');
                    continue;
                }
                String rendered = renderEvent(event, finalOutput, metrics);
                if (rendered != null && !rendered.isBlank()) {
                    liveLog.append(sessionKey, rendered);
                }
            }
        } catch (IOException e) {
            liveLog.append(sessionKey, "✗ Error reading session output: " + e.getMessage());
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

    /**
     * Mutable holder for run metrics captured off the terminal {@code result}
     * event by the streaming reader thread and read back once it finishes.
     */
    private static final class RunMetrics {
        Double costUsd;
        Long durationMs;
    }
}
