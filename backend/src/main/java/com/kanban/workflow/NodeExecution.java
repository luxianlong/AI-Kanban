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
 * <p>The one snapshotted field a user may change afterwards is {@link #prompt}, and
 * only on a finished run — see {@link #editPrompt}. That is how a prompt gets tried:
 * edit this run's copy, re-run the one step, read what came out. The workflow itself
 * is left alone, so an experiment costs one session rather than a graph.
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
     * was created, unless {@link #editPrompt} has since changed it for this run alone.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /**
     * When {@link #prompt} was last edited on this run, or null if it is still the
     * workflow's own text. Kept because a finished step's output was produced by the
     * prompt as it read <em>then</em>: once this stamp is later than
     * {@link #finishedAt}, what is on screen no longer answers what is in the box, and
     * the page says so rather than letting the two quietly disagree.
     */
    private Instant promptEditedAt;

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

    public Instant getPromptEditedAt() {
        return promptEditedAt;
    }

    /**
     * Change the prompt this run will use for its next attempt of this node, without
     * touching the workflow the run came from.
     *
     * <p>An execution is a record of what happened, so rewriting part of it is not
     * free: the output already on the row was produced by the previous text. The stamp
     * is what keeps the record honest — it dates the edit against
     * {@link #getFinishedAt()}, so a step showing an answer from the old prompt can be
     * labelled as one instead of reading as the new prompt's work.
     *
     * <p>An unchanged text is not an edit and leaves the stamp alone; otherwise
     * opening the box and closing it again would mark a step as stale.
     */
    void editPrompt(String newPrompt) {
        if (Objects.equals(prompt, newPrompt)) {
            return;
        }
        this.prompt = newPrompt;
        this.promptEditedAt = Instant.now();
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
     */
    void resetForRetry() {
        this.attempt = getAttempt() + 1;
        this.status = RunState.PENDING;
        this.input = null;
        this.output = null;
        this.error = null;
        this.startedAt = null;
        this.finishedAt = null;
    }
}
