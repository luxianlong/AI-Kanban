package com.kanban.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A reusable <em>workflow</em>: a directed acyclic graph of prompt tasks.
 *
 * <p>The nodes ({@link WorkflowNode}) each carry a user-written prompt; the edges
 * ({@link WorkflowEdge}) say which node feeds which. Running one produces a
 * {@link WorkflowExecution}, and each node run inside it is a <em>fresh, isolated</em>
 * Claude session — nothing is resumed and no conversation state is shared between
 * nodes. The only thing that crosses an edge is the upstream node's final output
 * text, which is pasted into the downstream node's input alongside its own prompt.
 *
 * <p>A workflow has exactly one {@link #entryNodeId entry node} — where every
 * execution starts.
 */
@Entity
@Table(name = "workflows")
public class Workflow {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    /** The single node an execution starts from. Always one of this workflow's nodes. */
    @Column(nullable = false)
    private String entryNodeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Workflow() {
    }

    public Workflow(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEntryNodeId() {
        return entryNodeId;
    }

    public void setEntryNodeId(String entryNodeId) {
        this.entryNodeId = entryNodeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
