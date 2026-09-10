package com.kanban.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One node's run inside a {@link WorkflowExecution}.
 *
 * <p>Everything the run needs is <em>snapshotted</em> here when the execution is
 * created — the node's name, its prompt, and the ids it depends on. That keeps a
 * finished execution an accurate record of what actually ran even after the
 * workflow it came from is edited or deleted, and it means an in-flight execution
 * can't have its shape changed underneath it.
 *
 * <p>On a <em>finished</em> run those snapshots may then be rewritten — name, prompt,
 * effort and wiring alike (see {@link #editSettings}) — and steps may be added to the
 * run ({@link #NodeExecution(Long, String, String, String, EffortLevel, List, int)}) or
 * dropped from it. That is how a graph gets debugged: change one thing in this run's
 * copy, re-run the one step, read what came out. The workflow itself is left alone, so
 * an experiment costs one session rather than a graph.
 *
 * <p>None of that is allowed while the run is live — see
 * {@code WorkflowService#requireSettled} — which is what keeps "exactly one thread
 * touches a run's rows" true.
 *
 * <p>{@link #input} is the text handed to this node from upstream: the concatenated
 * {@link #output}s of its dependencies (or the execution's own input, for the entry
 * node). Each run is an isolated Claude session — this text is the <em>only</em>
 * context that crosses the edge.
 */
@Entity
@Table(name = "workflow_node_executions")
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long executionId;

    /** The graph node this run came from (may no longer exist). */
    @Column(nullable = false)
    private String nodeId;

    @Column(nullable = false)
    private String nodeName;

    /**
     * Snapshot of the node's user-written prompt — as the workflow had it when the run
     * was created, unless {@link #editSettings} has since changed it for this run alone.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /**
     * When this run's copy of the step was last changed — its prompt, its effort or
     * what feeds it — or null while it is still exactly what the workflow said.
     *
     * <p>Kept because a finished step's output was produced by the settings as they
     * read <em>then</em>: once this stamp is later than {@link #finishedAt}, what is on
     * screen no longer answers what is in the boxes, and the page says so rather than
     * letting the two quietly disagree.
     *
     * <p>A rename does <em>not</em> touch it. The name is a label on the row; it is the
     * only one of the four settings that cannot change a single character of what the
     * session is asked to do, so stamping it would put a "this answer is out of date"
     * warning over an answer that is perfectly current.
     *
     * <p>The column is still {@code prompt_edited_at}: it was named when the prompt was
     * the only thing editable, and pinning it keeps the stamps already recorded rather
     * than starting a second column beside them.
     */
    @Column(name = "prompt_edited_at")
    private Instant settingsEditedAt;

    /**
     * When this step was inserted into the run, or null on the ordinary case — a step
     * the run snapshotted from the workflow when it was created.
     *
     * <p>An execution is a record of what happened, and a step the workflow never had
     * is the one thing in it that no amount of reading the workflow would explain. The
     * stamp is what lets the page mark it as added rather than let it pass as part of
     * the original graph.
     */
    private Instant addedAt;

    /**
     * Snapshot of the node's effort level — the thinking budget this step's session
     * was started with, or null for the CLI's default.
     *
     * <p>Snapshotted for the same reason the prompt is: raising a node's effort in the
     * editor should not silently rewrite what a finished run says it did. It is also
     * what a re-run of one step uses, so re-running a step redoes the work the run
     * recorded rather than the work the workflow would do today.
     */
    @Enumerated(EnumType.STRING)
    private EffortLevel effort;

    /**
     * Comma-separated ids of the nodes whose output feeds this one, snapshotted from
     * the graph's edges. Empty for the entry node. Stored flat rather than as a join
     * table because it is only ever read whole, for this one row.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String dependsOn;

    /** Position in a topological order of the graph — the run's display order. */
    @Column(nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunState status = RunState.PENDING;

    /** What upstream handed this node (assembled just before the session starts). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String input;

    /** What this node's session produced — the input of every downstream node. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String output;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String error;

    /**
     * The step's session transcript, kept so it can still be read once the step is done.
     *
     * <p>{@code output} is the answer this step handed downstream; this is the working —
     * the tool calls, the retries, the reasoning aloud. Live it is in memory, in
     * {@code LiveLogRegistry}, which loses it at the next restart; this column is what
     * makes "why did that step take twenty minutes?" answerable the following day. Written
     * once when the step settles, and cleared by {@link #resetForRetry()} so a row shows
     * the transcript of the attempt it is reporting on and no other.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String sessionLog;

    private Instant startedAt;

    private Instant finishedAt;

    private Double costUsd;

    private Long durationMs;

    /**
     * Which attempt this row is recording, counting from 1. A node is retried in
     * place rather than as a second row: the run is a picture of the graph, and two
     * rows for one node would draw the same box twice. Null on rows written before
     * retries existed — {@link #getAttempt()} reads that as the first attempt.
     */
    private Integer attempt;

    protected NodeExecution() {
    }

    public NodeExecution(Long executionId, WorkflowNode node, List<String> dependsOn, int orderIndex) {
        this.executionId = executionId;
        this.nodeId = node.getNodeId();
        this.nodeName = node.getName();
        this.prompt = node.getPrompt();
        this.effort = node.getEffort();
        this.dependsOn = String.join(",", dependsOn);
        this.orderIndex = orderIndex;
    }

    /**
     * A step added to a run that has already finished — one the workflow it came from
     * never had. It starts {@link RunState#PENDING} with nothing behind it, so the run
     * shows it as queued until the user starts it.
     *
     * <p>Its {@code nodeId} is minted by the service rather than taken from a client:
     * ids are only unique within a run, and a caller picking one could land on a node
     * that is already there and quietly become a second row for it.
     */
    NodeExecution(Long executionId, String nodeId, String nodeName, String prompt,
                  EffortLevel effort, List<String> dependsOn, int orderIndex) {
        this.executionId = executionId;
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.prompt = prompt;
        this.effort = effort;
        this.dependsOn = String.join(",", dependsOn);
        this.orderIndex = orderIndex;
        this.addedAt = Instant.now();
    }

    /** The snapshotted dependency ids, as a list (empty for the entry node). */
    public List<String> dependencyIds() {
        if (dependsOn == null || dependsOn.isBlank()) {
            return List.of();
        }
        return List.of(dependsOn.split(","));
    }

    public Long getId() {
        return id;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getPrompt() {
        return prompt;
    }

    public EffortLevel getEffort() {
        return effort;
    }

    public String getDependsOn() {
        return dependsOn;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    /**
     * Renumber this row's place in the run's display order. Called after a step is
     * inserted or removed, when the whole run is re-numbered from a fresh topological
     * order of what it now contains — an inserted step has to land between the steps it
     * sits between, not at the end of the list.
     */
    void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public RunState getStatus() {
        return status;
    }

    public void setStatus(RunState status) {
        this.status = status;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getSessionLog() {
        return sessionLog;
    }

    public void setSessionLog(String sessionLog) {
        this.sessionLog = sessionLog;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(Double costUsd) {
        this.costUsd = costUsd;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getSettingsEditedAt() {
        return settingsEditedAt;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    /**
     * Rewrite this run's copy of the step — everything a step <em>is</em> — without
     * touching the workflow the run came from.
     *
     * <p>All four settings and not just the prompt, because the question a debugging
     * session asks is rarely only about wording: was this step worth the effort level
     * it was given, and would it have answered better reading the step two above it as
     * well? Both used to mean editing the workflow and paying for the whole graph to
     * find out.
     *
     * <p>An execution is a record of what happened, so rewriting part of it is not
     * free: the output already on the row was produced by the previous settings. The
     * stamp is what keeps the record honest — it dates the edit against
     * {@link #getFinishedAt()}, so a step showing the old settings' answer can be
     * labelled as one instead of reading as the new ones' work.
     *
     * <p>An unchanged value is not an edit and leaves the stamp alone; otherwise
     * opening a box and closing it again would mark a step as stale. A rename never
     * stamps at all — see {@link #settingsEditedAt}.
     */
    void editSettings(String newName, String newPrompt, EffortLevel newEffort, List<String> newDependsOn) {
        this.nodeName = newName;
        if (!Objects.equals(prompt, newPrompt) || effort != newEffort) {
            this.prompt = newPrompt;
            this.effort = newEffort;
            this.settingsEditedAt = Instant.now();
        }
        rewire(newDependsOn);
    }

    /**
     * Change which of the run's steps feed this one. Its own method as well as part of
     * {@link #editSettings} because removing a step from a run rewires the steps that
     * read it — they inherit what the removed step read — without anyone editing them.
     *
     * <p>Stamped like any other change of settings: what is on the row was written from
     * the old input, and after a rewire the step is answering a different question.
     */
    void rewire(List<String> newDependsOn) {
        if (dependencyIds().equals(List.copyOf(newDependsOn))) {
            return;
        }
        this.dependsOn = String.join(",", newDependsOn);
        this.settingsEditedAt = Instant.now();
    }

    public int getAttempt() {
        return attempt == null ? 1 : attempt;
    }

    /**
     * Clear the last attempt so the runner queues this node again, keeping everything
     * the run snapshotted — name, prompt, dependencies. A retry re-runs the node the
     * <em>run</em> recorded, not whatever the workflow says today; an execution is a
     * record of one particular graph, and half-updating it would be a record of neither.
     *
     * <p>{@link #costUsd} and {@link #durationMs} are deliberately <em>not</em> cleared.
     * They are what this node has cost so far, and a failed attempt was still billed —
     * the runner adds the next attempt's figures to them.
     *
     * <p>The counter moves only for a row that had left the queue. A row still
     * {@link RunState#PENDING} has nothing behind it to count — a step just inserted
     * into the run is exactly that — and calling its first run "attempt 2" would put a
     * history on the page that never happened.
     */
    void resetForRetry() {
        if (status != RunState.PENDING) {
            this.attempt = getAttempt() + 1;
        }
        this.status = RunState.PENDING;
        this.input = null;
        this.output = null;
        this.error = null;
        this.sessionLog = null;
        this.startedAt = null;
        this.finishedAt = null;
    }
}
