package com.kanban.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the Claude Code worker, bound from the {@code worker.*}
 * namespace in {@code application.yml} (override via env vars, e.g.
 * {@code WORKER_ENABLED=false}).
 */
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    /** Master switch. When false, no Claude sessions are ever launched. */
    private boolean enabled = true;

    /** Path to the Claude Code CLI binary. */
    private String claudeCommand = "claude";

    /**
     * Extra CLI args inserted before the prompt. To let the session act
     * autonomously (edit files, run commands) without prompting, add
     * {@code --dangerously-skip-permissions} here — understand the risk first.
     */
    private List<String> extraArgs = List.of("--output-format", "stream-json", "--verbose");

    /** Working directory the Claude session runs in (its sandbox/repo). */
    private String workingDirectory = System.getProperty("java.io.tmpdir") + "/kanban-work";

    /** Hard timeout for a single item, in seconds (default 30 min). */
    private long timeoutSeconds = 1800;

    /**
     * How long a session may wait, after its last turn, for background work it started
     * — a dynamic workflow, a background subagent — before the CLI gives up and
     * terminates that work. Passed to the CLI as
     * {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS}; {@code 0} means wait indefinitely.
     *
     * <p>This exists because the CLI's own default is 600s, which is far shorter than
     * {@link #timeoutSeconds} and is applied silently: a session that hands its real
     * work to a dynamic workflow ends its turn with "I'll report back when it's done",
     * the CLI waits 600s, kills the workflow, and then emits that same stand-in
     * sentence as the session's answer. The session looks successful and its answer
     * describes results nobody ever received.
     *
     * <p>Waiting is what the session needs, and waiting works: when the background work
     * lands inside the ceiling the CLI hands the session a fresh turn, and the answer it
     * writes then is the one that gets reported. So the only question is how long, and
     * the answer should be "as long as this session is allowed to live" rather than a
     * number three times smaller that nothing here chose.
     *
     * <p>Deliberately a little under {@link #timeoutSeconds} rather than equal to it.
     * The two clocks start at different moments — this one when the last turn ends, the
     * hard timeout when the process starts — so leaving slack is what lets the CLI wind
     * up and report cost, duration and a reason, instead of being force-killed with
     * none of them. If the turns themselves eat the slack the hard timeout still fires
     * first, which is a worse report but not a wrong one.
     */
    private long backgroundWaitSeconds = 1500;

    /** Max pending items handled per poll tick. */
    private int batchSize = 1;

    /**
     * How many times the worker may <em>start</em> a single task before it stops
     * re-running it. A task orphaned in {@link com.kanban.todo.TodoStatus#IN_PROGRESS}
     * by a restart is normally returned to the queue so a crashed session is
     * retried — but a restart is often a sibling improvement task's guarded-deploy
     * restart that killed this task mid-run, and blindly re-running duplicates work
     * it may have already done. Once a task has been started this many times it is
     * routed to Needs Attention instead of re-queued. Default {@code 2} allows one
     * automatic recovery retry, then stops. Set to {@code 1} to never auto-retry.
     */
    private int maxAttempts = 2;

    /** How often the worker polls for pending items, in milliseconds. */
    private long pollIntervalMs = 15000;

    /**
     * When true, a task that isn't caught by the fast keyword classifier gets a
     * second, smarter pass: a short headless Claude call decides which existing
     * story (if any) it belongs to, and re-files it. This is what lets semantic /
     * non-English tasks join a story when they share no literal keyword. Runs only
     * when {@link #enabled} is also true (it uses the same CLI).
     */
    private boolean classifyWithClaude = true;

    /** Hard timeout for one background classification call, in seconds. */
    private long classifyTimeoutSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClaudeCommand() {
        return claudeCommand;
    }

    public void setClaudeCommand(String claudeCommand) {
        this.claudeCommand = claudeCommand;
    }

    public List<String> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getBackgroundWaitSeconds() {
        return backgroundWaitSeconds;
    }

    public void setBackgroundWaitSeconds(long backgroundWaitSeconds) {
        this.backgroundWaitSeconds = backgroundWaitSeconds;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public boolean isClassifyWithClaude() {
        return classifyWithClaude;
    }

    public void setClassifyWithClaude(boolean classifyWithClaude) {
        this.classifyWithClaude = classifyWithClaude;
    }

    public long getClassifyTimeoutSeconds() {
        return classifyTimeoutSeconds;
    }

    public void setClassifyTimeoutSeconds(long classifyTimeoutSeconds) {
        this.classifyTimeoutSeconds = classifyTimeoutSeconds;
    }
}
