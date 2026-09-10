package com.kanban.work;

import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moving a workflow run forward: what may start next, what can no longer start, and when the
 * run is over.
 *
 * <p>All of this used to happen inside the lane that was also running the node's session, so
 * "advance the graph" and "run a step" were one act. With the session on another machine they
 * come apart: the graph is advanced here, on the server, when a node is claimed and again when
 * its result arrives. Nothing about the rules changes — a node still waits for every
 * dependency to have SUCCEEDED, a failed node still poisons the branch below it, and a run
 * still ends when nothing is left that could run.
 */
@Component
public class WorkflowGraphProgress {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphProgress.class);

    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeRuns;

    public WorkflowGraphProgress(WorkflowExecutionRepository executions,
                                 NodeExecutionRepository nodeRuns) {
        this.executions = executions;
        this.nodeRuns = nodeRuns;
    }

    public Map<String, NodeExecution> byNodeId(List<NodeExecution> runs) {
        Map<String, NodeExecution> byNodeId = new HashMap<>();
        for (NodeExecution run : runs) {
            byNodeId.put(run.getNodeId(), run);
        }
        return byNodeId;
    }

    /**
     * Mark every still-queued node whose upstream will never succeed as
     * {@link RunState#SKIPPED}, repeating until it settles so the skip propagates all
     * the way down the branch. Without this a failed node would leave everything
     * behind it queued forever.
     */
    public void skipUnreachableWork(List<NodeExecution> runs, Map<String, NodeExecution> byNodeId) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeExecution run : runs) {
                if (run.getStatus() != RunState.PENDING) {
                    continue;
                }
                for (String dependency : run.dependencyIds()) {
                    NodeExecution upstream = byNodeId.get(dependency);
                    boolean deadEnd = upstream == null
                            || (upstream.getStatus().isTerminal() && upstream.getStatus() != RunState.SUCCEEDED);
                    if (deadEnd) {
                        run.setStatus(RunState.SKIPPED);
                        run.setError("Skipped: the node it depends on ("
                                + (upstream == null ? dependency : "\"" + upstream.getNodeName() + "\"")
                                + ") did not succeed.");
                        run.setFinishedAt(Instant.now());
                        nodeRuns.save(run);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    public boolean dependenciesSucceeded(NodeExecution run, Map<String, NodeExecution> byNodeId) {
        for (String dependency : run.dependencyIds()) {
            NodeExecution upstream = byNodeId.get(dependency);
            if (upstream == null || upstream.getStatus() != RunState.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /**
     * The text handed to this node from upstream — the <em>only</em> thing that
     * crosses an edge, since each node runs in its own fresh session.
     *
     * <p>The entry node gets whatever the user typed when starting the run (possibly
     * nothing). Every other node gets its dependencies' outputs, each labelled with
     * the node it came from so a node with two parents can tell them apart.
     */
    public String assembleInput(WorkflowExecution execution, NodeExecution run,
                                Map<String, NodeExecution> byNodeId) {
        List<String> dependencies = run.dependencyIds();
        if (dependencies.isEmpty()) {
            return execution.getInput() == null ? "" : execution.getInput();
        }
        StringBuilder sb = new StringBuilder();
        for (String dependency : dependencies) {
            NodeExecution upstream = byNodeId.get(dependency);
            if (upstream == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("— Output of node \"").append(upstream.getNodeName()).append("\":\n");
            String output = upstream.getOutput();
            if (output == null || output.isBlank()) {
                sb.append("(this node produced no output)\n");
            } else {
                sb.append(output).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The whole prompt for one node: the text it was handed, then the node's own
     * instructions. Nothing else, on any node — the entry step included.
     *
     * <p>Deliberately free of scaffolding. Everything the runner used to prepend (the node's
     * name and place in the graph, "you are a fresh session", the failure sentinel, a
     * guardrail naming directories) was the only concrete filesystem hint a node ever saw, and
     * nodes acted on it: they wrote their material into those directories instead of replying
     * with it, and the nodes downstream went hunting through them rather than reading the input
     * they had been given.
     */
    public String buildPrompt(NodeExecution run, String input) {
        String instructions = run.getPrompt() == null ? "" : run.getPrompt().strip();
        String handed = input == null ? "" : input.strip();
        if (handed.isEmpty()) {
            return instructions;
        }
        if (instructions.isEmpty()) {
            return handed;
        }
        return handed + "\n\n" + instructions;
    }

    /**
     * Move a run to RUNNING the first time something of it starts.
     *
     * <p>{@code startedAt} is stamped only once: a retried run is queued again, but it still
     * began when it began, and restamping it would erase how long it has really been going.
     */
    public void markStarted(WorkflowExecution execution) {
        if (execution.getStatus() == RunState.PENDING) {
            execution.setStatus(RunState.RUNNING);
            if (execution.getStartedAt() == null) {
                execution.setStartedAt(Instant.now());
            }
            executions.save(execution);
            log.info("Workflow execution #{} ({}) started.", execution.getId(),
                    execution.getWorkflowName());
        }
    }

    /**
     * Close a run out if it has nothing left that could run.
     *
     * <p>Called after every node result. With execution on another machine there is no lane
     * sitting inside the run to notice it has finished, so this is where a run ends.
     */
    public void finishIfSettled(WorkflowExecution execution) {
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        if (runs.isEmpty()) {
            finish(execution, runs, "This run has no nodes.");
            return;
        }
        skipUnreachableWork(runs, byNodeId(runs));
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.RUNNING)) {
            return; // something is still going
        }
        Map<String, NodeExecution> byNodeId = byNodeId(runs);
        boolean anyRunnable = runs.stream()
                .anyMatch(r -> r.getStatus() == RunState.PENDING && dependenciesSucceeded(r, byNodeId));
        if (anyRunnable) {
            return; // more work to hand out
        }
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.PENDING)) {
            // Nothing ready, nothing running, yet work is left: the snapshot's
            // dependencies don't resolve. Close the run out rather than spinning.
            finish(execution, runs, "Stuck: some nodes never became runnable. "
                    + "Their recorded dependencies don't resolve — re-save the workflow and try again.");
            return;
        }
        finish(execution, runs, null);
    }

    /** Close an execution out, deriving its outcome from the nodes' own outcomes. */
    public void finish(WorkflowExecution execution, List<NodeExecution> runs, String error) {
        RunState status = RunState.SUCCEEDED;
        if (error != null) {
            status = RunState.FAILED;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == RunState.FAILED)) {
            status = RunState.FAILED;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == RunState.CANCELLED)) {
            status = RunState.CANCELLED;
        }
        double cost = 0;
        boolean anyCost = false;
        for (NodeExecution run : runs) {
            if (run.getCostUsd() != null) {
                cost += run.getCostUsd();
                anyCost = true;
            }
        }
        execution.setStatus(status);
        execution.setError(error);
        execution.setCostUsd(anyCost ? cost : null);
        execution.setFinishedAt(Instant.now());
        executions.save(execution);
        log.info("Workflow execution #{} finished: {}.", execution.getId(), status);
    }
}
