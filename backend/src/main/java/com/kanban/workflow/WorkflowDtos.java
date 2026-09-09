package com.kanban.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response payloads for the workflow API. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /**
     * One node in a saved graph. {@code id} is chosen by the editor (a UUID) so the
     * same payload can carry the edges that reference it; the server keeps whatever
     * ids it is given rather than renumbering, which is what makes saving an edited
     * graph a plain replace.
     */
    public record NodeSpec(
            @NotBlank @Size(max = 100) String id,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20_000) String prompt,
            Integer x,
            Integer y) {
    }

    /** One dependency: {@code from}'s output feeds {@code to}'s input. */
    public record EdgeSpec(
            @NotBlank @Size(max = 100) String from,
            @NotBlank @Size(max = 100) String to) {
    }

    /**
     * Create or replace a workflow. The whole graph is sent every time — the editor
     * holds the working copy and saves it wholesale, so there is no partial-update
     * path that could leave nodes and edges disagreeing.
     *
     * <p>{@code entryNodeId} may be omitted when exactly one node has no incoming
     * edge; that node is then the entry.
     */
    public record SaveWorkflowRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2_000) String description,
            @NotEmpty @Size(max = 50) List<@Valid NodeSpec> nodes,
            @Size(max = 200) List<@Valid EdgeSpec> edges,
            @Size(max = 100) String entryNodeId) {
    }

    /** Start a run, optionally handing the entry node some starting text. */
    public record RunWorkflowRequest(
            @Size(max = 20_000) String input) {
    }

    public record NodeView(String id, String name, String prompt, Integer x, Integer y) {
        static NodeView from(WorkflowNode n) {
            return new NodeView(n.getNodeId(), n.getName(), n.getPrompt(), n.getPosX(), n.getPosY());
        }
    }

    public record EdgeView(String from, String to) {
        static EdgeView from(WorkflowEdge e) {
            return new EdgeView(e.getFromNodeId(), e.getToNodeId());
        }
    }

    /** A workflow with its full graph — everything the editor needs to render it. */
    public record WorkflowView(
            String id,
            String name,
            String description,
            String entryNodeId,
            Instant createdAt,
            Instant updatedAt,
            List<NodeView> nodes,
            List<EdgeView> edges) {

        static WorkflowView from(Workflow w, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
            return new WorkflowView(
                    w.getId(),
                    w.getName(),
                    w.getDescription(),
                    w.getEntryNodeId(),
                    w.getCreatedAt(),
                    w.getUpdatedAt(),
                    nodes.stream().map(NodeView::from).toList(),
                    edges.stream().map(EdgeView::from).toList());
        }
    }

    /** One node's run: what went in, what came out, and how it went. */
    public record NodeRunView(
            Long id,
            String nodeId,
            String nodeName,
            String prompt,
            List<String> dependsOn,
            int orderIndex,
            RunState status,
            String input,
            String output,
            String error,
            Instant startedAt,
            Instant finishedAt,
            Double costUsd,
            Long durationMs) {

        static NodeRunView from(NodeExecution n) {
            return new NodeRunView(
                    n.getId(),
                    n.getNodeId(),
                    n.getNodeName(),
                    n.getPrompt(),
                    n.dependencyIds(),
                    n.getOrderIndex(),
                    n.getStatus(),
                    n.getInput(),
                    n.getOutput(),
                    n.getError(),
                    n.getStartedAt(),
                    n.getFinishedAt(),
                    n.getCostUsd(),
                    n.getDurationMs());
        }
    }

    /**
     * A run and every node in it. {@code doneNodes}/{@code totalNodes} are computed
     * server-side so the list and the detail agree on progress without the client
     * re-deriving it.
     */
    public record ExecutionView(
            Long id,
            String workflowId,
            String workflowName,
            RunState status,
            String input,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Double costUsd,
            int doneNodes,
            int totalNodes,
            List<NodeRunView> nodes) {

        static ExecutionView from(WorkflowExecution e, List<NodeExecution> nodes) {
            long done = nodes.stream().filter(n -> n.getStatus().isTerminal()).count();
            return new ExecutionView(
                    e.getId(),
                    e.getWorkflowId(),
                    e.getWorkflowName(),
                    e.getStatus(),
                    e.getInput(),
                    e.getError(),
                    e.getCreatedAt(),
                    e.getStartedAt(),
                    e.getFinishedAt(),
                    e.getCostUsd(),
                    (int) done,
                    nodes.size(),
                    nodes.stream().map(NodeRunView::from).toList());
        }
    }
}
