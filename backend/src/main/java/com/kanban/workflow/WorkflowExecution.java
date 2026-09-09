package com.kanban.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One run of a {@link Workflow} — what the "Executions" side of the workflow page
 * lists. It owns a {@link NodeExecution} per node of the graph, created up front so
 * the whole run is visible (queued nodes included) from the moment it starts.
 *
 * <p>The workflow's name is copied in so an execution still reads correctly after
 * the workflow is renamed or deleted.
 */
@Entity
@Table(name = "workflow_executions")
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workflowId;

    /** Snapshot of the workflow's name at run time. */
    @Column(nullable = false)
    private String workflowName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunState status = RunState.PENDING;

    /**
     * Optional free text the user supplied when starting the run. It is handed to
     * the entry node as its input, which is what lets one workflow be re-run against
     * different subjects without editing its prompts.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String input;

    /** Why the run ended the way it did, when that isn't obvious from the nodes. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    /** Sum of what every node run in this execution reported spending. */
    private Double costUsd;

    protected WorkflowExecution() {
    }

    public WorkflowExecution(String workflowId, String workflowName, String input) {
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.input = input;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
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

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
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
}
