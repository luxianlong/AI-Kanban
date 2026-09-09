package com.kanban.workflow;

import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.RunningSessionRegistry;
import com.kanban.workflow.WorkflowDtos.EdgeSpec;
import com.kanban.workflow.WorkflowDtos.ExecutionView;
import com.kanban.workflow.WorkflowDtos.NodeSpec;
import com.kanban.workflow.WorkflowDtos.SaveWorkflowRequest;
import com.kanban.workflow.WorkflowDtos.WorkflowView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything the workflow API does apart from actually running sessions (that is
 * {@link WorkflowRunnerService}): storing and validating graphs, starting runs, and
 * assembling the views the workflow page renders.
 */
@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowNodeRepository nodes;
    private final WorkflowEdgeRepository edges;
    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeRuns;
    private final RunningSessionRegistry sessions;
    private final LiveLogRegistry liveLog;

    /** How many runs the executions panel lists at once. */
    private static final int EXECUTION_PAGE = 50;

    public WorkflowService(WorkflowRepository workflows, WorkflowNodeRepository nodes, WorkflowEdgeRepository edges,
                           WorkflowExecutionRepository executions, NodeExecutionRepository nodeRuns,
                           RunningSessionRegistry sessions, LiveLogRegistry liveLog) {
        this.workflows = workflows;
        this.nodes = nodes;
        this.edges = edges;
        this.executions = executions;
        this.nodeRuns = nodeRuns;
        this.sessions = sessions;
        this.liveLog = liveLog;
    }

    // --- workflows -------------------------------------------------------

    public List<WorkflowView> list() {
        return workflows.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    public WorkflowView get(String id) {
        return view(find(id));
    }

    @Transactional
    public WorkflowView create(SaveWorkflowRequest req) {
        Workflow workflow = new Workflow(UUID.randomUUID().toString(), req.name().trim());
        return save(workflow, req);
    }

    @Transactional
    public WorkflowView replace(String id, SaveWorkflowRequest req) {
        Workflow workflow = find(id);
        workflow.setName(req.name().trim());
        return save(workflow, req);
    }

    /**
     * Write a workflow's graph, replacing whatever it had. Validation runs first, so
     * an invalid graph (a cycle, a dangling edge, no usable entry) is rejected whole
     * rather than half-written.
     */
    private WorkflowView save(Workflow workflow, SaveWorkflowRequest req) {
        List<NodeSpec> nodeSpecs = req.nodes();
        List<EdgeSpec> edgeSpecs = req.edges() == null ? List.of() : req.edges();
        List<String> nodeIds = nodeSpecs.stream().map(NodeSpec::id).toList();
        List<WorkflowGraph.Edge> graphEdges = edgeSpecs.stream()
                .map(e -> new WorkflowGraph.Edge(e.from(), e.to()))
                .toList();

        String entry = req.entryNodeId() == null || req.entryNodeId().isBlank()
                ? WorkflowGraph.inferEntry(nodeIds, graphEdges)
                : req.entryNodeId();
        if (entry == null) {
            throw badRequest("A workflow needs exactly one entry node — either mark one, "
                    + "or leave exactly one node with no incoming connection.");
        }
        String problem = new WorkflowGraph(nodeIds, graphEdges, entry).validate();
        if (problem != null) {
            throw badRequest(problem);
        }

        workflow.setDescription(req.description());
        workflow.setEntryNodeId(entry);
        workflows.save(workflow);

        // Replace the graph wholesale: the editor always sends the complete picture,
        // so diffing node-by-node would only add ways for the two to drift apart.
        nodes.deleteByWorkflowId(workflow.getId());
        edges.deleteByWorkflowId(workflow.getId());
        nodes.flush();
        edges.flush();
        for (NodeSpec n : nodeSpecs) {
            nodes.save(new WorkflowNode(n.id(), workflow.getId(), n.name().trim(), n.prompt(),
                    n.x(), n.y()));
        }
        for (EdgeSpec e : edgeSpecs) {
            edges.save(new WorkflowEdge(workflow.getId(), e.from(), e.to()));
        }
        return view(workflow);
    }

    /**
     * Copy a workflow into a brand new one — the usual way a variant of a working
     * graph gets started, without retyping every prompt.
     *
     * <p>The copy is made from the stored rows rather than from anything the client
     * sends, so it carries fields the editor doesn't round-trip (a node's canvas
     * position) and cannot be used to smuggle in a graph that was never validated.
     * Node ids are kept verbatim: they are unique only within a workflow, so the
     * edges copy across untouched and the copy reads in the editor exactly as the
     * original does.
     *
     * <p>Run history is deliberately <em>not</em> copied. An execution is a record of
     * something that happened, and the copy has never run.
     */
    @Transactional
    public WorkflowView duplicate(String id) {
        Workflow original = find(id);
        Set<String> taken = new HashSet<>(workflows.findAllByOrderByCreatedAtDesc().stream()
                .map(Workflow::getName).toList());

        Workflow copy = new Workflow(UUID.randomUUID().toString(), copyName(original.getName(), taken));
        copy.setDescription(original.getDescription());
        copy.setEntryNodeId(original.getEntryNodeId());
        workflows.save(copy);

        for (WorkflowNode n : nodes.findByWorkflowIdOrderByIdAsc(id)) {
            nodes.save(new WorkflowNode(n.getNodeId(), copy.getId(), n.getName(), n.getPrompt(),
                    n.getPosX(), n.getPosY()));
        }
        for (WorkflowEdge e : edges.findByWorkflowId(id)) {
            edges.save(new WorkflowEdge(copy.getId(), e.getFromNodeId(), e.getToNodeId()));
        }
        return view(copy);
    }

    /** Longest name the API accepts ({@code SaveWorkflowRequest.name}). */
    private static final int MAX_NAME = 200;

    /** A trailing "(copy)" or "(copy 7)" that {@link #copyName} put there before. */
    private static final Pattern COPY_SUFFIX = Pattern.compile("\\s*\\(copy(?: \\d+)?\\)$");

    /**
     * Name for a copy of {@code original}: "Research (copy)", then "(copy 2)" and on,
     * skipping names already in use so the list never shows two identical rows.
     *
     * <p>An existing suffix is stripped first — duplicating a duplicate should give
     * "Research (copy 3)", not "Research (copy) (copy) (copy)". The base is truncated
     * so the result still fits {@link #MAX_NAME}; a copy the editor could not save
     * back would be a trap.
     */
    static String copyName(String original, Set<String> taken) {
        String stripped = original == null ? "" : COPY_SUFFIX.matcher(original.trim()).replaceFirst("").trim();
        String base = stripped.isEmpty() ? "Workflow" : stripped;
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? " (copy)" : " (copy " + n + ")";
            int room = MAX_NAME - suffix.length();
            String candidate = (base.length() > room ? base.substring(0, room).trim() : base) + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Delete a workflow, its graph <em>and</em> its run history. An execution has no
     * meaning without the workflow it came from, and leaving orphans behind would
     * keep them on the executions panel with nothing to open.
     */
    @Transactional
    public void delete(String id) {
        Workflow workflow = find(id);
        for (WorkflowExecution e : executions.findByWorkflowIdOrderByCreatedAtDesc(id)) {
            deleteExecutionRows(e);
        }
        nodes.deleteByWorkflowId(id);
        edges.deleteByWorkflowId(id);
        workflows.delete(workflow);
    }

    private WorkflowView view(Workflow w) {
        return WorkflowView.from(w, nodes.findByWorkflowIdOrderByIdAsc(w.getId()), edges.findByWorkflowId(w.getId()));
    }

    private Workflow find(String id) {
        return workflows.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow not found: " + id));
    }

    // --- executions ------------------------------------------------------

    /**
     * Queue a run of the workflow. Every node gets its {@link NodeExecution} row up
     * front — snapshotting its name, prompt and dependencies — so the run's shape is
     * fixed at this moment and the page can show the whole graph's progress
     * immediately. Nodes the entry can't reach are recorded as
     * {@link RunState#SKIPPED} rather than left silently absent.
     *
     * <p>The run itself is picked up by {@link WorkflowRunnerService} on its next
     * tick; nothing is launched here.
     */
    @Transactional
    public ExecutionView run(String workflowId, String input) {
        Workflow workflow = find(workflowId);
        List<WorkflowNode> graphNodes = nodes.findByWorkflowIdOrderByIdAsc(workflowId);
        List<WorkflowEdge> graphEdges = edges.findByWorkflowId(workflowId);
        WorkflowGraph graph = new WorkflowGraph(
                graphNodes.stream().map(WorkflowNode::getNodeId).toList(),
                graphEdges.stream().map(e -> new WorkflowGraph.Edge(e.getFromNodeId(), e.getToNodeId())).toList(),
                workflow.getEntryNodeId());
        String problem = graph.validate();
        if (problem != null) {
            throw badRequest("This workflow can't run: " + problem);
        }

        WorkflowExecution execution = executions.save(
                new WorkflowExecution(workflowId, workflow.getName(), blankToNull(input)));

        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode n : graphNodes) {
            byId.put(n.getNodeId(), n);
        }
        Set<String> reachable = graph.reachable();
        List<String> order = graph.topologicalOrder();
        int index = 0;
        for (String nodeId : order) {
            NodeExecution run = new NodeExecution(
                    execution.getId(), byId.get(nodeId), graph.dependenciesOf(nodeId), index++);
            if (!reachable.contains(nodeId)) {
                run.setStatus(RunState.SKIPPED);
                run.setError("Not reachable from the entry node, so this run never gets to it.");
            }
            nodeRuns.save(run);
        }
        return executionView(execution);
    }

    /** Runs of one workflow, or of all of them when {@code workflowId} is null. */
    public List<ExecutionView> listExecutions(String workflowId) {
        List<WorkflowExecution> found = workflowId == null || workflowId.isBlank()
                ? executions.findAllByOrderByCreatedAtDesc()
                : executions.findByWorkflowIdOrderByCreatedAtDesc(workflowId);
        List<WorkflowExecution> page = found.size() > EXECUTION_PAGE
                ? found.subList(0, EXECUTION_PAGE)
                : found;
        if (page.isEmpty()) {
            return List.of();
        }
        // One query for every listed run's nodes rather than one per run.
        Map<Long, List<NodeExecution>> byExecution = new HashMap<>();
        for (NodeExecution n : nodeRuns.findByExecutionIdInOrderByOrderIndexAsc(
                page.stream().map(WorkflowExecution::getId).toList())) {
            byExecution.computeIfAbsent(n.getExecutionId(), k -> new ArrayList<>()).add(n);
        }
        return page.stream()
                .map(e -> ExecutionView.from(e, byExecution.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    public ExecutionView getExecution(Long executionId) {
        return executionView(findExecution(executionId));
    }

    /**
     * Stop a run on the user's request: kill the node session that is live right now
     * (if any) and mark everything that hadn't finished as cancelled/skipped, so the
     * run reaches a terminal state immediately instead of waiting for the runner.
     */
    @Transactional
    public ExecutionView cancel(Long executionId) {
        WorkflowExecution execution = findExecution(executionId);
        if (execution.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Execution #" + executionId + " has already finished (" + execution.getStatus() + ").");
        }
        for (NodeExecution run : nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId)) {
            if (run.getStatus() == RunState.RUNNING) {
                // Killing the process makes the runner's own wait return; it reads the
                // stop flag back and leaves the row exactly as we set it here.
                sessions.stop(LiveLogRegistry.nodeKey(run.getId()));
                liveLog.append(LiveLogRegistry.nodeKey(run.getId()),
                        "● Cancelled by user — terminating the session…");
                run.setStatus(RunState.CANCELLED);
                run.setError("Cancelled by user.");
                run.setFinishedAt(Instant.now());
                nodeRuns.save(run);
            } else if (run.getStatus() == RunState.PENDING) {
                run.setStatus(RunState.SKIPPED);
                run.setError("The run was cancelled before this node started.");
                run.setFinishedAt(Instant.now());
                nodeRuns.save(run);
            }
        }
        execution.setStatus(RunState.CANCELLED);
        execution.setError("Cancelled by user.");
        execution.setFinishedAt(Instant.now());
        executions.save(execution);
        return executionView(execution);
    }

    @Transactional
    public void deleteExecution(Long executionId) {
        deleteExecutionRows(findExecution(executionId));
    }

    /** Drop one run's rows and the in-memory transcripts of its node sessions. */
    private void deleteExecutionRows(WorkflowExecution execution) {
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        for (NodeExecution run : runs) {
            liveLog.clear(LiveLogRegistry.nodeKey(run.getId()));
        }
        nodeRuns.deleteAll(runs);
        executions.delete(execution);
    }

    /** Live transcript of one node's session, for polling while it runs. */
    public Map<String, Object> nodeLog(Long nodeRunId) {
        NodeExecution run = nodeRuns.findById(nodeRunId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Node run not found: " + nodeRunId));
        return Map.of(
                "id", run.getId(),
                "status", run.getStatus(),
                "log", liveLog.get(LiveLogRegistry.nodeKey(run.getId())));
    }

    private ExecutionView executionView(WorkflowExecution execution) {
        return ExecutionView.from(execution,
                nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId()));
    }

    private WorkflowExecution findExecution(Long id) {
        return executions.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Execution not found: " + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
