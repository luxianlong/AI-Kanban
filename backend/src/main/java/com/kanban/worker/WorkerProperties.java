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
    private String workingDirectory = System.getProperty("java.io.tmpdir") + "/dashboard-work";

    /** Hard timeout for a single item, in seconds (default 30 min). */
    private long timeoutSeconds = 1800;

    /** Max pending items handled per poll tick. */
    private int batchSize = 1;

    /** How often the worker polls for pending items, in milliseconds. */
    private long pollIntervalMs = 15000;

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

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
