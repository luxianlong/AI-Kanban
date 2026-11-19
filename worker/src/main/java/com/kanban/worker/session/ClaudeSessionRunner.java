package com.kanban.worker.session;

import com.kanban.protocol.SessionLimitsDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.protocol.SessionLimitsDto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one headless Copilot CLI session and reports what it produced.
 *
 * <p>This is the single place that knows how to launch the CLI, enforce the
 * timeout, and turn {@code --output-format json} events into readable log
 * lines. Both things that run sessions use it — {@link ClaudeWorkerService} for a
 * board task and {@code WorkflowRunnerService} for one node of a workflow — so the
 * live log, the stop button and the shutdown abort behave identically for both.
 *
 * <p>Each call is a <em>fresh</em> session: nothing is resumed and no conversation
 * state is shared between runs. Whatever context a run needs must be written into
 * its prompt by the caller.
 */
public class ClaudeSessionRunner {

    /**
     * Agents print this line to mark their work as failed even when the CLI exits 0
     * (e.g. a guarded deploy whose tests failed). Every caller checks the final
     * output for it, so it lives with the runner rather than in one of them.
     */
    public static final String FAIL_SENTINEL = "[[TASK_FAILED]]";

    /**
     * The {@code warningType} the CLI reports in a {@code session.warning} event when
     * it stops waiting for background work a session started and terminates it.
     *
     * <p>This is a structured, typed field rather than a text line to match. The
     * terminal {@code result} event that follows looks exactly like a clean finish, and
     * the session's answer is whatever it said <em>before</em> the wait — typically a
     * promise to report back about work that has just been destroyed. Without this the
     * run is recorded as a success holding a stand-in for an answer.
     */
    static final String BACKGROUND_WORK_ABANDONED_WARNING_TYPE = "background_task_wait_timeout";

    private final SessionSettings settings;
    private final SessionLog liveLog;
    private final RunningSessions sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeSessionRunner(SessionSettings settings, SessionLog liveLog,
                               RunningSessions sessions) {
        this.settings = settings;
        this.liveLog = liveLog;
        this.sessions = sessions;
    }

    /**
     * What one finished session produced.
     *
     * @param abandonedBackgroundWork the session ended while background work it had
     *                                started was still running, so the CLI terminated
     *                                that work — {@link #finalOutput()} was written
     *                                before it and cannot describe its results
     */
    public record ProcessResult(int exitCode, String finalOutput, boolean timedOut,
                                Double costUsd, Long durationMs,
                                boolean abandonedBackgroundWork) {
    }

    /**
     * Launch {@code copilot -p <prompt>} and block until it finishes or hits the timeout.
     *
     * <p>One entry point, because everything that used to vary between callers now arrives
     * from the server in the work unit: the directory to run in, the per-unit CLI args (the
     * effort level) and both clocks. The worker applies them without needing to know why
     * they differ.
     *
     * @param sessionKey the key this run logs under and can be stopped by
     */
    public ProcessResult run(String sessionKey, String prompt, File workDir,
                             List<String> sessionArgs, SessionLimitsDto limits)
            throws IOException, InterruptedException {
        if (!workDir.exists() && !workDir.mkdirs()) {
            liveLog.append(sessionKey, "! Could not create working directory " + workDir);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(settings.claudeCommand());
        cmd.addAll(settings.extraArgs());
        cmd.addAll(sessionArgs);
        cmd.add("-p");
        cmd.add(prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true);
        // Worker sessions are one-shot task runners, not a continuing conversation with the
        // user, but nothing needs to be done about that here: unlike Claude Code, Copilot
        // CLI's memory auto-save is opt-in (--enable-memory) and already off by default in
        // non-interactive/-p mode.
        //
        // How long the CLI may wait, after the last turn, for background work this session
        // started before killing it. Its own default is 600s, which is both much shorter
        // than the timeout enforced below and silent when it fires — see
        // WorkerProperties#getBackgroundWaitSeconds. Waiting is the whole point: work that
        // lands inside the ceiling gets the session another turn, and that turn is what the
        // caller reads back. It comes from the same SessionLimitsDto as the timeout below,
        // which is what stops the two drifting apart.
        //
        // Unlike Claude Code's CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS (milliseconds, 0 = wait
        // indefinitely), Copilot CLI's COPILOT_TASK_WAIT_TIMEOUT_SECONDS takes seconds and
        // treats 0 as "don't wait at all" — the opposite sentinel, and there is no documented
        // way to ask Copilot to wait indefinitely. A configured wait of 0 is approximated here
        // by waiting as long as the whole process is allowed to live: the hard destroyForcibly
        // below still fires at limits.timeoutSeconds() regardless, so this can never actually
        // outlive the process, only ask the CLI not to give up on the wait before that happens.
        long backgroundWaitSeconds = limits.backgroundWaitSeconds() > 0
                ? limits.backgroundWaitSeconds() : limits.timeoutSeconds();
        pb.environment().put("COPILOT_TASK_WAIT_TIMEOUT_SECONDS",
                Long.toString(backgroundWaitSeconds));
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

            boolean finished = process.waitFor(limits.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                reader.join(5_000);
                return new ProcessResult(-1, finalOutput.toString(), true, metrics.costUsd,
                        metrics.durationMs, metrics.abandonedBackgroundWork);
            }
            reader.join(5_000);
            return new ProcessResult(process.exitValue(), finalOutput.toString(), false,
                    metrics.costUsd, metrics.durationMs, metrics.abandonedBackgroundWork);
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
                    // Not JSON (e.g. text output format, or a CLI argument error printed
                    // before any session starts) — stream verbatim.
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
     * Turns one Copilot CLI JSON event into a readable log line.
     *
     * <p>Unlike Claude Code's stream-json, Copilot's terminal {@code result} event
     * carries no answer text — only exit/usage bookkeeping. The final answer instead
     * has to be read off the last {@code assistant.message} event's {@code data.content},
     * which this overwrites into {@code finalOutput} every time a non-blank one arrives
     * (a turn that only makes tool calls reports an empty {@code content} and is
     * skipped, so the field always ends up holding the session's last real reply).
     *
     * <p>Returns {@code null} to skip an event that has nothing worth showing — most of
     * the stream is Copilot's own internal/ephemeral bookkeeping (deltas, idle pings,
     * tool-loading, usage checkpoints) that would just be noise in the live log.
     */
    private String renderEvent(JsonNode e, StringBuilder finalOutput, RunMetrics metrics) {
        String type = e.path("type").asText("");
        JsonNode data = e.path("data");
        switch (type) {
            case "session.model_change" -> {
                // The very first model_change (no previousModel yet) is the model the
                // session actually started with; every later one is a mid-session switch,
                // which is not worth a log line of its own.
                if (!data.has("previousModel")) {
                    return "● Session started · model " + data.path("newModel").asText("?");
                }
                return null;
            }
            case "assistant.message" -> {
                String text = data.path("content").asText("").strip();
                if (text.isBlank()) {
                    return null;
                }
                finalOutput.setLength(0);
                finalOutput.append(text);
                return "🤖 " + text;
            }
            case "tool.execution_start" -> {
                return "🔧 " + data.path("toolName").asText("tool")
                        + "  " + summarizeInput(data.path("arguments"));
            }
            case "tool.execution_complete" -> {
                boolean ok = data.path("success").asBoolean(true);
                return (ok ? "   ↳ ✓ " : "   ↳ ✗ ")
                        + firstLine(data.path("result").path("content").asText(""), 160);
            }
            case "session.warning" -> {
                String warningType = data.path("warningType").asText("");
                if (BACKGROUND_WORK_ABANDONED_WARNING_TYPE.equals(warningType)) {
                    // Recorded rather than acted on here: this event arrives before the
                    // terminal result event (which carries no answer of its own), while
                    // finalOutput was already set by the session's last assistant.message
                    // — the stand-in reply it wrote before the wait ran out.
                    metrics.abandonedBackgroundWork = true;
                }
                return "⚠ " + data.path("message").asText(warningType);
            }
            case "result" -> {
                long durationMs = e.path("usage").path("sessionDurationMs").asLong(0);
                int premiumRequests = e.path("usage").path("premiumRequests").asInt(0);
                metrics.durationMs = durationMs > 0 ? durationMs : null;
                // Copilot CLI reports usage in premium requests, not a dollar figure —
                // there is no equivalent of Claude's total_cost_usd, so costUsd is left
                // unset (ProcessResult#costUsd stays null for every Copilot-run session).
                return String.format("✔ Completed in %.1fs · %d premium request(s)",
                        durationMs / 1000.0, premiumRequests);
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
        boolean abandonedBackgroundWork;
    }
}
