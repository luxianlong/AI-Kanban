package com.kanban.worker;

import com.kanban.protocol.SessionLimitsDto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the agent worker, bound from the {@code worker.*}
 * namespace in {@code application.yml} (override via env vars, e.g.
 * {@code WORKER_ENABLED=false}).
 */
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    /** Master switch. When false, no agent sessions are ever launched. */
    private boolean enabled = true;

    /** Path to the Copilot CLI binary. */
    private String claudeCommand = "copilot";

    /**
     * Extra CLI args inserted before the prompt. {@code --allow-all-tools} is what lets
     * the session act autonomously (edit files, run commands) without prompting —
     * understand the risk first.
     */
    private List<String> extraArgs = List.of("--output-format", "json", "--allow-all-tools");

    /** Working directory the Claude session runs in (its sandbox/repo). */
    private String workingDirectory = System.getProperty("java.io.tmpdir") + "/kanban-work";

    /** Hard timeout for a single item, in seconds (default 30 min). */
    private long timeoutSeconds = 1800;

    /**
     * How long a session may wait, after its last turn, for background work it started
     * — a dynamic workflow, a background subagent — before the CLI gives up and
     * terminates that work. Passed to the CLI as
     * {@code COPILOT_TASK_WAIT_TIMEOUT_SECONDS} (seconds, not milliseconds); {@code 0}
     * here is treated as "wait as long as the session is allowed to live" — Copilot CLI
     * has no "wait indefinitely" sentinel, and (unlike Claude Code) its own {@code 0}
     * means the opposite: stop waiting immediately. See
     * {@link com.kanban.worker.session.ClaudeSessionRunner#run} for how that gap is
     * bridged.
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
     *
     * <p>Left unset by default, because "a little under {@link #timeoutSeconds}" is a
     * relationship and writing it down as a number does not keep it. It was written down
     * as 1500 against a timeout of 1800, and then {@code scripts/env.sh} launched the
     * backend with {@code --worker.timeout-seconds=3600} — so the live system gave a
     * session an hour to live and killed the dynamic workflow it started after 25
     * minutes, which is the same silent mismatch this setting exists to remove, just
     * with different numbers. Unset means {@link #getBackgroundWaitSeconds()} derives it,
     * so raising the timeout raises the wait with it. A value set here (or a negative
     * one, meaning "derive") still wins; {@code 0} still means "as long as the session
     * may live" (see the Copilot CLI caveat above).
     */
    private Long backgroundWaitSeconds;

    /**
     * How much shorter than {@link #timeoutSeconds} a derived wait is — the slack the
     * CLI needs to wind up and report a cost, a duration and a reason once the wait runs
     * out, instead of being force-killed with none of them. 300s is the gap the numbers
     * this replaces were chosen with (1500 against 1800).
     */
    static final long BACKGROUND_WAIT_HEADROOM_SECONDS = 300;

    /**
     * The shortest derived wait, for a timeout small enough that subtracting the headroom
     * would leave nothing. A minute of waiting is not much, but it is a wait; zero would
     * silently mean "wait indefinitely" and outlive the timeout it was derived from.
     */
    static final long MIN_DERIVED_BACKGROUND_WAIT_SECONDS = 60;

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
     * second, smarter pass: a short headless CLI call decides which existing
     * story (if any) it belongs to, and re-files it. This is what lets semantic /
     * non-English tasks join a story when they share no literal keyword. Runs only
     * when {@link #enabled} is also true (it uses the same CLI). The field is named
     * {@code classifyWithClaude} for config-key stability; it now runs whatever CLI
     * {@link #claudeCommand} points at.
     */
    private boolean classifyWithClaude = true;

    /** Hard timeout for one background classification call, in seconds. */
    private long classifyTimeoutSeconds = 45;

    /**
     * Master switch for handing work out at all.
     *
     * <p>Read by {@code AgentService.claim} rather than by a poller: the server no longer runs
     * sessions, so this is the only point where "run nothing" can still be enforced. It also
     * still gates the story classifier, which is one of the few things the server does shell
     * out for.
     */
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

    /**
     * The wait actually handed to a session, in seconds — never negative, and derived
     * from {@link #getTimeoutSeconds()} when nothing was configured.
     *
     * <p>This is what every caller wants, which is why the derivation lives behind the
     * plain getter rather than beside it: a second method would be one a caller could
     * reach for the raw field by mistake, and the mistake would be invisible until a
     * step's dynamic workflow was killed with half its allowance unspent.
     */
    public long getBackgroundWaitSeconds() {
        return backgroundWaitSecondsFor(timeoutSeconds);
    }

    /**
     * The same wait, for a session that is not living for {@link #getTimeoutSeconds()} —
     * a workflow step given a longer ceiling than the worker's own, say.
     *
     * <p>The derivation is the point: whoever lengthens a session gets the longer wait
     * for free, and cannot lengthen the one without the other. What does <em>not</em>
     * scale is an explicitly configured wait, and that is deliberate — the setting is
     * documented as pinning the wait regardless of the timeout, {@code 0} means "wait
     * indefinitely", and an operator who wrote a number down meant that number rather
     * than a starting point to multiply.
     */
    public long backgroundWaitSecondsFor(long sessionTimeoutSeconds) {
        if (backgroundWaitSeconds != null && backgroundWaitSeconds >= 0) {
            return backgroundWaitSeconds;
        }
        return Math.max(MIN_DERIVED_BACKGROUND_WAIT_SECONDS,
                sessionTimeoutSeconds - BACKGROUND_WAIT_HEADROOM_SECONDS);
    }

    /** The pair of clocks a session runs against on this configuration. */
    public SessionLimitsDto sessionLimits() {
        return sessionLimitsWithTimeout(timeoutSeconds);
    }

    /**
     * The same pair for a session allowed to live longer (or shorter) than the
     * configured timeout — the only way to build a {@link SessionLimitsDto}, so a caller
     * that changes the ceiling gets a wait that matches it rather than one left behind.
     */
    public SessionLimitsDto sessionLimitsWithTimeout(long sessionTimeoutSeconds) {
        return new SessionLimitsDto(sessionTimeoutSeconds,
                backgroundWaitSecondsFor(sessionTimeoutSeconds));
    }

    /** What was configured, or {@code null} when the wait is derived from the timeout. */
    public Long getConfiguredBackgroundWaitSeconds() {
        return backgroundWaitSeconds;
    }

    public void setBackgroundWaitSeconds(Long backgroundWaitSeconds) {
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
