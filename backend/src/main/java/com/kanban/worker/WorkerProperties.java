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
