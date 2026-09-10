package com.kanban.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response payloads for the workflow API. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /**
     * One node in a saved graph. {@code id} is chosen by the editor (a UUID) so the
     * same payload can carry the edges that reference it; the server keeps whatever
     * ids it is given rather than renumbering, which is what makes saving an edited
     * graph a plain replace.
     *
     * <p>{@code effort} is one of {@link EffortLevel#allowed()}, or null/blank to leave
     * this step at the CLI's default. It is a plain string rather than the enum so that
     * a bad value is rejected with a sentence naming the levels, instead of Jackson's
     * deserialisation error — and so that the casing the picker sends ("xhigh") is the
     * casing the API takes.
     */
    public record NodeSpec(
            @NotBlank @Size(max = 100) String id,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20_000) String prompt,
            @Size(max = 20) String effort,
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

    /**
     * Keep a step aside to use again, or overwrite the one already under this name.
     *
     * <p>Same caps as {@link NodeSpec}'s corresponding fields, so a step that saves in
     * the editor also saves as a favourite — and a favourite always inserts back into
     * the editor without being truncated on the way.
     */
    public record SaveFavoriteStepRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20_000) String prompt,
            @Size(max = 20) String effort) {
    }

    /** A favourited step, ready to be written into a graph as a new node. */
    public record FavoriteStepView(
            String id, String name, String prompt, String effort, Instant updatedAt) {

        static FavoriteStepView from(FavoriteStep f) {
            return new FavoriteStepView(f.getId(), f.getName(), f.getPrompt(),
                    f.getEffort() == null ? null : f.getEffort().cliValue(),
                    f.getUpdatedAt());
        }
    }

    /** Start a run, optionally handing the entry node some starting text. */
    public record RunWorkflowRequest(
            @Size(max = 20_000) String input) {
    }

    /**
     * Rewrite one step's prompt inside one finished run — the run's own copy, not the
     * workflow's. Same 20,000-character cap as {@link NodeSpec#prompt()}, so text that
     * saves in the editor also saves here.
     *
     * <p>The narrow form of {@link EditNodeRunRequest}, kept because changing the
     * wording alone is what this is used for nine times in ten and it needs no answer
     * to "what should the other three settings be".
     */
    public record EditNodePromptRequest(
            @Size(max = 20_000) String prompt) {
    }

    /**
     * Everything one step in a finished run <em>is</em>: its name, its prompt, how hard
     * it thinks, and which of the run's other steps feed it. The whole set is sent every
     * time — the same wholesale-replace rule {@link SaveWorkflowRequest} follows, and for
     * the same reason: there is then no partial-update path where a step's wiring and its
     * prompt can be left disagreeing about which edit won.
     *
     * <p>Caps match {@link NodeSpec}'s, so a step that saves in the editor also saves
     * here, and a wording tried in a run copies back into the workflow untruncated.
     *
     * <p>{@code inputs} are node ids <em>within this run</em>. Empty is right for the
     * entry step and refused for every other one — a step nothing feeds is not wired in
     * below the entry, so the run would never reach it.
     */
    public record EditNodeRunRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20_000) String prompt,
            @Size(max = 20) String effort,
            @Size(max = 50) List<@NotBlank @Size(max = 100) String> inputs) {
    }

    /**
     * A step to add to a finished run — one the workflow it came from never had.
     *
     * <p>Same fields as {@link EditNodeRunRequest} minus the id, which the server mints:
     * a node id is unique only within its run, so a caller choosing one could land on a
     * step already there and silently become a second row for it.
     *
     * <p>{@code inputs} may not be empty here, entry step or not. The run already has an
     * entry and it is the step it started from; a second root added afterwards would be
     * a branch nothing leads to, recorded as part of a run that could never have reached
     * it.
     */
    public record InsertNodeRunRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20_000) String prompt,
            @Size(max = 20) String effort,
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 100) String> inputs) {
    }

    /** {@code effort} is null when the step runs at the CLI's default. */
    public record NodeView(String id, String name, String prompt, String effort, Integer x, Integer y) {
        static NodeView from(WorkflowNode n) {
            return new NodeView(n.getNodeId(), n.getName(), n.getPrompt(),
                    n.getEffort() == null ? null : n.getEffort().cliValue(),
                    n.getPosX(), n.getPosY());
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

    /**
     * One node's run: what went in, what came out, and how it went.
     *
     * <p>{@code attempt} is 1 unless the run was retried; {@code costUsd} and
     * {@code durationMs} are then the totals across every attempt, since a failed
     * attempt was billed too.
     *
     * <p>{@code effort} is the level this step's session was started with, snapshotted
     * when the run was created — null means it ran at the CLI's default. It does not
     * follow later edits to the workflow, so a finished run keeps saying what it did.
     *
     * <p>{@code settingsEditedAt} is null while this step is still exactly what the
     * workflow said. Once set, and later than {@code finishedAt}, the output on this row
     * was produced by the previous settings — which is what the page warns about, so an
     * edited-but-not-yet-re-run step doesn't read as the new ones' work. A rename does
     * not set it; see {@link NodeExecution}.
     *
     * <p>{@code addedAt} is null on a step the run snapshotted from the workflow, and
     * set on one inserted into the run afterwards — the one thing in the record that
     * reading the workflow would never explain.
     */
    public record NodeRunView(
            Long id,
            String nodeId,
            String nodeName,
            String prompt,
            String effort,
            Instant settingsEditedAt,
            Instant addedAt,
            List<String> dependsOn,
            int orderIndex,
            RunState status,
            String input,
            String output,
            String error,
            Instant startedAt,
            Instant finishedAt,
            Double costUsd,
            Long durationMs,
            int attempt,
            int rerunFromCount,
            boolean canRerunAlone) {

        static NodeRunView from(NodeExecution n, int rerunFromCount, boolean canRerunAlone) {
            return new NodeRunView(
                    n.getId(),
                    n.getNodeId(),
                    n.getNodeName(),
                    n.getPrompt(),
                    n.getEffort() == null ? null : n.getEffort().cliValue(),
                    n.getSettingsEditedAt(),
                    n.getAddedAt(),
                    n.dependencyIds(),
                    n.getOrderIndex(),
                    n.getStatus(),
                    n.getInput(),
                    n.getOutput(),
                    n.getError(),
                    n.getStartedAt(),
                    n.getFinishedAt(),
                    n.getCostUsd(),
                    n.getDurationMs(),
                    n.getAttempt(),
                    rerunFromCount,
                    canRerunAlone);
        }
    }

    /**
     * A run and every node in it. {@code doneNodes}/{@code totalNodes} are computed
     * server-side so the list and the detail agree on progress without the client
     * re-deriving it.
     *
     * <p>{@code retryableNodes} is how many steps a retry of this run would re-run —
     * 0 when there is nothing to retry, which is how the page decides whether to offer
     * the button. It is derived server-side for the same reason: which steps a retry
     * touches is the retry's own rule (see {@link WorkflowService#nodesToRetry}), and
     * a client re-deriving it would eventually disagree with the server that acts on it.
     *
     * <p>Each node carries the same number for itself in {@code rerunFromCount}: how
     * many steps re-running <em>that</em> step would redo, itself included. 0 means the
     * step can't be started by hand — the run is still going, or the entry never
     * reached it (see {@link WorkflowService#nodesToRerunFrom}).
     *
     * <p>{@code canRerunAlone} is the narrower question the other button asks: whether
     * that step can be redone <em>without</em> the ones below it. It is false wherever
     * {@code rerunFromCount} is 0, and false as well on a step whose upstream never
     * succeeded — there is no stored input to hand it a second time
     * (see {@link WorkflowService#canRerunAlone}).
     *
     * <p>{@code entryNodeId} is the step this run started from, snapshotted with the
     * rest of the graph. The page needs it by name rather than by position: the entry
     * step is drawn differently, and a topological order can legitimately put another
     * node first. Null on runs recorded before the entry was snapshotted.
     */
    public record ExecutionView(
            Long id,
            String workflowId,
            String workflowName,
            RunState status,
            String entryNodeId,
            String input,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Double costUsd,
            int doneNodes,
            int totalNodes,
            int retryableNodes,
            List<NodeRunView> nodes) {

        static ExecutionView from(WorkflowExecution e, List<NodeExecution> nodes, int retryableNodes,
                                  Map<String, Integer> rerunFromCounts,
                                  Map<String, Boolean> rerunAloneFlags) {
            long done = nodes.stream().filter(n -> n.getStatus().isTerminal()).count();
            return new ExecutionView(
                    e.getId(),
                    e.getWorkflowId(),
                    e.getWorkflowName(),
                    e.getStatus(),
                    e.getEntryNodeId(),
                    e.getInput(),
                    e.getError(),
                    e.getCreatedAt(),
                    e.getStartedAt(),
                    e.getFinishedAt(),
                    e.getCostUsd(),
                    (int) done,
                    nodes.size(),
                    retryableNodes,
                    nodes.stream()
                            .map(n -> NodeRunView.from(n,
                                    rerunFromCounts.getOrDefault(n.getNodeId(), 0),
                                    rerunAloneFlags.getOrDefault(n.getNodeId(), false)))
                            .toList());
        }
    }
}
