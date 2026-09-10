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
import jakarta.persistence.UniqueConstraint;

/**
 * One task in a {@link Workflow}'s graph: a name plus the user-written
 * {@link #prompt} that node's Claude session is asked to carry out.
 *
 * <p>The prompt is only <em>half</em> of what the session receives — the other half
 * is the output of whatever node(s) feed this one, assembled at run time (see
 * {@code WorkflowRunnerService}). A node's prompt should therefore read as
 * "what to do with the input", not as a standalone task, unless it is the entry node.
 *
 * <p>{@link #nodeId} is chosen by the client and is unique only <em>within</em> its
 * workflow, so it cannot be the primary key: two workflows are perfectly entitled to
 * both call a node "a", and using the client's id as the PK made saving the second
 * one silently reassign the first one's rows. The table therefore has a surrogate
 * key, with {@code (workflowId, nodeId)} unique instead.
 */
@Entity
@Table(name = "workflow_nodes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workflowId", "nodeId"}))
public class WorkflowNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String workflowId;

    /** The id the editor gave this node; edges reference it. Unique per workflow. */
    @Column(nullable = false)
    private String nodeId;

    @Column(nullable = false)
    private String name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /**
     * How hard this step's session thinks, or null to leave it at the CLI's default.
     *
     * <p>Stored on the node rather than on the workflow because it is a property of
     * the <em>work</em>: within one graph a step that has to reason its way to an
     * answer and a step that tidies the paragraph it was handed do not want the same
     * setting, and a single graph-wide dial can only be right for one of them.
     *
     * <p>Null is a real value here and not a missing one — see {@link EffortLevel}.
     */
    @Enumerated(EnumType.STRING)
    private EffortLevel effort;

    /** Editor layout only — where the node sits on the canvas. Never used at run time. */
    private Integer posX;

    private Integer posY;

    protected WorkflowNode() {
    }

    public WorkflowNode(String nodeId, String workflowId, String name, String prompt,
                        EffortLevel effort, Integer posX, Integer posY) {
        this.nodeId = nodeId;
        this.workflowId = workflowId;
        this.name = name;
        this.prompt = prompt;
        this.effort = effort;
        this.posX = posX;
        this.posY = posY;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public EffortLevel getEffort() {
        return effort;
    }

    public Integer getPosX() {
        return posX;
    }

    public Integer getPosY() {
        return posY;
    }
}
