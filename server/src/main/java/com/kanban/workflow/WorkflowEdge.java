package com.kanban.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A dependency between two nodes of a {@link Workflow}: {@link #fromNodeId}'s
 * output becomes part of {@link #toNodeId}'s input. Edges are directed and the
 * graph must stay acyclic (enforced by {@link WorkflowGraph#validate}).
 */
@Entity
@Table(name = "workflow_edges")
public class WorkflowEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workflowId;

    @Column(nullable = false)
    private String fromNodeId;

    @Column(nullable = false)
    private String toNodeId;

    protected WorkflowEdge() {
    }

    public WorkflowEdge(String workflowId, String fromNodeId, String toNodeId) {
        this.workflowId = workflowId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }
}
