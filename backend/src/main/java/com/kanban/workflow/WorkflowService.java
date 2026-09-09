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
import java.util.LinkedHashSet;
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

        WorkflowExecution execution = new WorkflowExecution(workflowId, workflow.getName(), blankToNull(input));
        execution.setEntryNodeId(workflow.getEntryNodeId());
        execution = executions.save(execution);

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
                .map(e -> view(e, byExecution.getOrDefault(e.getId(), List.of())))
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

    /**
     * Queue a finished run again from where it broke: every step that didn't succeed
     * goes back to PENDING and the runner picks the execution up on its next tick.
     *
     * <p>The steps that <em>did</em> succeed keep their output, which is the whole
     * point — a workflow node is a Claude session that costs real money and real
     * minutes, so re-running a six-step graph because the last step timed out pays
     * five times over for work that already came out fine. The failed step is handed
     * exactly the input it was handed before, because that input is its upstream's
     * stored output and nothing upstream has moved.
     *
     * <p>Two things this deliberately does not do. It does not pick up edits to the
     * workflow — the run replays the prompts it snapshotted, since an execution is a
     * record of one particular graph and half-updating it would be a record of
     * neither. And it does not touch a step the entry could never reach; see
     * {@link #retryableNodeIds}.
     */
    @Transactional
    public ExecutionView retry(Long executionId) {
        WorkflowExecution execution = findExecution(executionId);
        if (!execution.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run #" + executionId + " is still going (" + execution.getStatus()
                            + ") — cancel it before starting it over.");
        }
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId);
        List<NodeExecution> retrying = nodesToRetry(runs, execution);
        if (retrying.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nothing to retry in run #" + executionId
                            + " — every step it could reach already succeeded.");
        }
        for (NodeExecution run : retrying) {
            // The transcript on this key belongs to the attempt that failed. Drop it
            // rather than let the new attempt append to it: the node's own `error`
            // field already keeps why it stopped, and a log that runs two attempts
            // together reads as one long session that contradicts itself.
            liveLog.clear(LiveLogRegistry.nodeKey(run.getId()));
            run.resetForRetry();
            nodeRuns.save(run);
        }
        // Back in the queue. startedAt is left alone — the run began when it began,
        // and the runner only stamps it when it is still unset.
        execution.setStatus(RunState.PENDING);
        execution.setError(null);
        execution.setFinishedAt(null);
        executions.save(execution);
        return executionView(execution);
    }

    /**
     * The node runs a retry of {@code execution} would re-run: everything that didn't
     * succeed, among the nodes the run could ever have reached.
     *
     * <p>Empty for a run still in flight and for one where everything worked, so its
     * size doubles as "can this run be retried at all" — which is what the page reads
     * off {@code ExecutionView.retryableNodes}.
     */
    static List<NodeExecution> nodesToRetry(List<NodeExecution> runs, WorkflowExecution execution) {
        if (!execution.getStatus().isTerminal()) {
            return List.of();
        }
        Set<String> reachable = retryableNodeIds(runs, execution.getEntryNodeId());
        return runs.stream()
                .filter(r -> r.getStatus() != RunState.SUCCEEDED && reachable.contains(r.getNodeId()))
                .toList();
    }

    /**
     * Which of a run's nodes a retry is allowed to touch: those reachable from the
     * entry node in the graph the run snapshotted.
     *
     * <p>The exclusion is the reason this exists. A node the entry cannot reach was
     * recorded SKIPPED the moment the run was created — the user had built it but not
     * wired it in — and, having no dependencies to wait on, it would look perfectly
     * runnable if a retry reset it to PENDING. The runner would then start a session
     * the original run deliberately never started, on a branch nobody asked to run.
     *
     * <p>Runs recorded before the entry was snapshotted don't know theirs. Those fall
     * back to the cone below whatever actually broke — the failed or cancelled nodes
     * and everything downstream of them. Anything downstream of a node the run reached
     * is reachable too, so the fallback is always a subset of the real answer: it can
     * retry less than it might, never something unwired.
     */
    static Set<String> retryableNodeIds(List<NodeExecution> runs, String entryNodeId) {
        if (knowsItsEntry(runs, entryNodeId)) {
            return snapshotGraph(runs, entryNodeId).reachable();
        }
        Set<String> cone = new LinkedHashSet<>();
        for (NodeExecution run : runs) {
            if (run.getStatus() == RunState.FAILED || run.getStatus() == RunState.CANCELLED) {
                cone.addAll(snapshotGraph(runs, run.getNodeId()).reachable());
            }
        }
        return cone;
    }

    /** Re-run one step and, with it, everything that reads its output. */
    @Transactional
    public ExecutionView retryNode(Long nodeRunId) {
        return retryNode(nodeRunId, true);
    }

    /**
     * Queue a finished run again from one particular step — <em>including</em> one that
     * succeeded. That step goes back to PENDING, along with everything downstream of it
     * when {@code downstream} is set; the run itself is re-queued and the runner picks
     * it up on the next tick.
     *
     * <p>This is the other half of {@link #retry(Long)}, and the difference is who
     * decides a step needs doing over. Retry asks the run: it takes the steps that
     * didn't succeed. This asks the user: a step can produce a perfectly successful
     * answer that is simply the wrong answer — a summary that missed the point, a draft
     * written before the source it should have used existed — and the only way to fix
     * it used to be running the whole graph from the top.
     *
     * <p>Everything downstream goes with it by default, and that default is the
     * cautious one. A node's input is its upstream's stored output, so redoing a step
     * invalidates every answer computed from the old one; leaving those in place shows
     * a run whose later steps describe material that is no longer there. Everything
     * <em>upstream</em> is untouched and keeps its output — that is what makes this
     * cheaper than a re-run.
     *
     * <p>{@code downstream = false} buys back the other half of that trade. Redoing a
     * nine-step chain from step two because step two's wording was off pays for seven
     * sessions to find out whether one of them helped, and the answer is usually
     * visible in step two's own output. So this variant redoes exactly the one step:
     * one session, one bill, and the steps below keep what they produced. What they
     * produced is then a fraction older than its own input, which is a real thing to
     * get wrong — so the run says so rather than letting it pass silently
     * (see {@code NodeRunView.inputChangedAfterRun} in the page's derivation). Once the
     * new output looks right, the cascade is the button next to it.
     *
     * <p>Two rules about what may be started. A step the entry could never reach stays
     * out, exactly as {@link #retryableNodeIds} has it, because starting it would run a
     * branch the user built but never wired in. And on its own, a step is refused
     * unless everything it depends on succeeded: with no cascade above it to produce
     * that input, the runner would take one look at the reset row, find its upstream
     * dead, and mark it SKIPPED — spending nothing, but throwing away the output the
     * row was holding. Whole-run retry is the answer for that case, and the message
     * says so.
     */
    @Transactional
    public ExecutionView retryNode(Long nodeRunId, boolean downstream) {
        NodeExecution target = nodeRuns.findById(nodeRunId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Node run not found: " + nodeRunId));
        WorkflowExecution execution = findExecution(target.getExecutionId());
        if (!execution.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run #" + execution.getId() + " is still going (" + execution.getStatus()
                            + ") — wait for it, or cancel it, before re-running one of its steps.");
        }
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        List<NodeExecution> rerunning = nodesToRerunFrom(runs, execution, target.getNodeId());
        if (rerunning.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Step \"" + target.getNodeName() + "\" can't be started on its own — this run "
                            + "never reached it, so it isn't wired in below the entry node.");
        }
        if (!downstream) {
            if (!dependenciesSucceeded(runs, target)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Step \"" + target.getNodeName() + "\" can't be run on its own — the step it "
                                + "reads from didn't succeed, so there is no input waiting for it. "
                                + "Retry the run, or re-run from the step that broke.");
            }
            rerunning = List.of(target);
        }
        for (NodeExecution run : rerunning) {
            // Same reason as in retry(): the transcript on this key belongs to the
            // attempt being replaced, and appending a second one to it would read as a
            // single session that contradicts itself.
            liveLog.clear(LiveLogRegistry.nodeKey(run.getId()));
            run.resetForRetry();
            nodeRuns.save(run);
        }
        execution.setStatus(RunState.PENDING);
        execution.setError(null);
        execution.setFinishedAt(null);
        executions.save(execution);
        return executionView(execution);
    }

    /**
     * Rewrite one step's prompt inside one finished run, without touching the workflow
     * it came from — the edit is this run's alone.
     *
     * <p>This exists so a prompt can be <em>tried</em>. Until now the only way to find
     * out whether a different wording produces a better answer was to edit the
     * workflow and run the whole graph, which pays for every step upstream of the one
     * in question and mixes the change in with a fresh set of everything else. Editing
     * the run's own copy and re-running that one step (see {@link #retryNode}) is the
     * same experiment with one variable and one session's bill: the step is handed
     * exactly the input it was handed before, so what changed is the prompt and only
     * the prompt.
     *
     * <p>Allowed on exactly the steps {@link #retryNode} would start, and refused on
     * the rest. That is not a coincidence — the whole reason to change the text is to
     * run it, so on a step that can never be run the edit would do nothing but falsify
     * the record of what the run did. It also keeps the page honest: the same
     * {@code rerunFromCount} that decides whether the re-run button is offered decides
     * whether the box is editable.
     *
     * <p>The workflow is deliberately left as it was. A wording that turns out to be
     * better is then copied into the editor by hand, which is the moment it stops being
     * an experiment and starts being what every future run does.
     */
    @Transactional
    public ExecutionView editNodePrompt(Long nodeRunId, String prompt) {
        NodeExecution target = nodeRuns.findById(nodeRunId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Node run not found: " + nodeRunId));
        WorkflowExecution execution = findExecution(target.getExecutionId());
        if (!execution.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run #" + execution.getId() + " is still going (" + execution.getStatus()
                            + ") — wait for it, or cancel it, before editing one of its prompts.");
        }
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        if (nodesToRerunFrom(runs, execution, target.getNodeId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Step \"" + target.getNodeName() + "\" can't be re-run in this run — it isn't "
                            + "wired in below the entry node — so editing its prompt would change "
                            + "the record without ever testing the change.");
        }
        target.editPrompt(blankToNull(prompt));
        nodeRuns.save(target);
        return executionView(execution);
    }

    /**
     * The node runs that re-running {@code nodeId} would redo: that node and every node
     * downstream of it in the graph the run snapshotted.
     *
     * <p>Empty — meaning "this step cannot be started by hand" — for a run still in
     * flight, and for a step the entry could never reach. Its size is what the page
     * puts on the button, so the count the user is shown and the set the service acts
     * on come out of the same function.
     */
    static List<NodeExecution> nodesToRerunFrom(List<NodeExecution> runs, WorkflowExecution execution,
                                                String nodeId) {
        if (!execution.getStatus().isTerminal() || !wasReached(runs, execution.getEntryNodeId(), nodeId)) {
            return List.of();
        }
        Set<String> cone = snapshotGraph(runs, nodeId).reachable();
        return runs.stream().filter(r -> cone.contains(r.getNodeId())).toList();
    }

    /** How many steps re-running each of a run's steps would redo, by node id. */
    static Map<String, Integer> rerunFromCounts(List<NodeExecution> runs, WorkflowExecution execution) {
        if (!execution.getStatus().isTerminal()) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (NodeExecution run : runs) {
            counts.put(run.getNodeId(), nodesToRerunFrom(runs, execution, run.getNodeId()).size());
        }
        return counts;
    }

    /**
     * Whether one step can be re-run <em>by itself</em>, leaving the steps below it
     * with the output they already have.
     *
     * <p>Everything {@link #nodesToRerunFrom} allows, minus the steps whose input never
     * arrived. Redoing a step alone hands it its upstream's stored output, so a step
     * whose upstream failed, was cancelled or was skipped has nothing to be handed:
     * the runner would mark the reset row SKIPPED on the next tick and the row's old
     * output would be gone for nothing. The cascade doesn't have that problem — it
     * resets the upstream too — which is why this is the narrower of the two rules and
     * not simply {@code rerunFromCount > 0}.
     */
    static boolean canRerunAlone(List<NodeExecution> runs, WorkflowExecution execution, String nodeId) {
        if (nodesToRerunFrom(runs, execution, nodeId).isEmpty()) {
            return false;
        }
        return runs.stream()
                .filter(r -> r.getNodeId().equals(nodeId))
                .findFirst()
                .map(target -> dependenciesSucceeded(runs, target))
                .orElse(false);
    }

    /** Which of a run's steps can be re-run on their own, by node id. */
    static Map<String, Boolean> rerunAloneFlags(List<NodeExecution> runs, WorkflowExecution execution) {
        if (!execution.getStatus().isTerminal()) {
            return Map.of();
        }
        Map<String, Boolean> flags = new HashMap<>();
        for (NodeExecution run : runs) {
            flags.put(run.getNodeId(), canRerunAlone(runs, execution, run.getNodeId()));
        }
        return flags;
    }

    /**
     * Whether every step this one reads from produced an answer — i.e. its input is
     * sitting there ready to be handed to it again.
     */
    private static boolean dependenciesSucceeded(List<NodeExecution> runs, NodeExecution target) {
        Map<String, RunState> states = new HashMap<>();
        for (NodeExecution run : runs) {
            states.put(run.getNodeId(), run.getStatus());
        }
        return target.dependencyIds().stream().allMatch(id -> states.get(id) == RunState.SUCCEEDED);
    }

    /**
     * Whether the run could ever have got to this node — i.e. it is wired in below the
     * entry, rather than a branch left dangling in the editor.
     *
     * <p>Runs recorded before the entry was snapshotted don't know theirs. For those,
     * a node that reached any state other than SKIPPED is proof enough that the run
     * walked to it. SKIPPED is the ambiguous one — it means either "unwired" or
     * "upstream didn't succeed" — so on those older runs it is refused, and the whole
     * failure is still retryable through {@link #retry(Long)}.
     */
    private static boolean wasReached(List<NodeExecution> runs, String entryNodeId, String nodeId) {
        if (knowsItsEntry(runs, entryNodeId)) {
            return snapshotGraph(runs, entryNodeId).reachable().contains(nodeId);
        }
        return runs.stream()
                .anyMatch(r -> r.getNodeId().equals(nodeId) && r.getStatus() != RunState.SKIPPED);
    }

    private static boolean knowsItsEntry(List<NodeExecution> runs, String entryNodeId) {
        return entryNodeId != null && runs.stream().anyMatch(r -> r.getNodeId().equals(entryNodeId));
    }

    /** The run's own snapshotted graph, rooted wherever the caller wants to walk from. */
    private static WorkflowGraph snapshotGraph(List<NodeExecution> runs, String entryNodeId) {
        List<String> nodeIds = runs.stream().map(NodeExecution::getNodeId).toList();
        List<WorkflowGraph.Edge> graphEdges = new ArrayList<>();
        for (NodeExecution run : runs) {
            for (String dependency : run.dependencyIds()) {
                graphEdges.add(new WorkflowGraph.Edge(dependency, run.getNodeId()));
            }
        }
        return new WorkflowGraph(nodeIds, graphEdges, entryNodeId);
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
        return view(execution, nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId()));
    }

    private static ExecutionView view(WorkflowExecution execution, List<NodeExecution> runs) {
        return ExecutionView.from(execution, runs, nodesToRetry(runs, execution).size(),
                rerunFromCounts(runs, execution), rerunAloneFlags(runs, execution));
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
