package com.kanban.work;

import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the next workflow node a worker may run, and takes it.
 *
 * <p><strong>Runs go in parallel; a single run stays sequential.</strong> That was true of the
 * in-process runner and stays true here, but for a different reason. It used to follow from an
 * execution occupying one lane of a thread pool; now it is enforced explicitly — an execution
 * with a node already RUNNING is skipped, so at most one of its nodes is out at a time even
 * where its graph has independent branches. Keeping that promise matters because so much of
 * the graph bookkeeping (skip propagation, closing the run out) reads a run's rows as a whole.
 *
 * <p>Oldest-first across executions is what keeps the queue fair between workflows: a workflow
 * started twice does not get served ahead of a run that has been waiting longer.
 */
@Component
public class WorkflowWorkSource {

    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeRuns;
    private final WorkflowGraphProgress graph;

    public WorkflowWorkSource(WorkflowExecutionRepository executions,
                              NodeExecutionRepository nodeRuns, WorkflowGraphProgress graph) {
        this.executions = executions;
        this.nodeRuns = nodeRuns;
        this.graph = graph;
    }

    /** One claimed node, with the run it belongs to and the text it was handed. */
    public record ClaimedNode(WorkflowExecution execution, NodeExecution run, String input) {
    }

    /**
     * Claim the next node that is ready to run, or empty if there is none.
     *
     * <p>A lost race is not an error — another worker got there first — so the search simply
     * moves on, which is what lets several workers drain a graph-heavy board together.
     */
    public Optional<ClaimedNode> claimNext() {
        List<WorkflowExecution> active =
                executions.findByStatusInOrderByCreatedAtAsc(List.of(RunState.PENDING, RunState.RUNNING));
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> busy = executionsWithSomethingRunning(active);

        for (WorkflowExecution execution : active) {
            if (busy.contains(execution.getId())) {
                continue;
            }
            Optional<ClaimedNode> claimed = claimFrom(execution);
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    private Set<Long> executionsWithSomethingRunning(List<WorkflowExecution> active) {
        List<Long> ids = active.stream().map(WorkflowExecution::getId).toList();
        Set<Long> busy = new HashSet<>();
        for (NodeExecution run : nodeRuns.findByExecutionIdInAndStatusIn(ids, List.of(RunState.RUNNING))) {
            busy.add(run.getExecutionId());
        }
        return busy;
    }

    private Optional<ClaimedNode> claimFrom(WorkflowExecution execution) {
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        if (runs.isEmpty()) {
            graph.finish(execution, runs, "This run has no nodes.");
            return Optional.empty();
        }
        Map<String, NodeExecution> byNodeId = graph.byNodeId(runs);
        graph.skipUnreachableWork(runs, byNodeId);

        for (NodeExecution run : runs) {
            if (!graph.readyToStart(run, byNodeId)) {
                continue;
            }
            String input = graph.assembleInput(execution, run, byNodeId);
            if (nodeRuns.claimIfPending(run.getId(), Instant.now(), input) == 0) {
                continue; // somebody else took it
            }
            NodeExecution claimed = nodeRuns.findById(run.getId()).orElse(null);
            if (claimed == null) {
                continue;
            }
            graph.markStarted(execution);
            return Optional.of(new ClaimedNode(execution, claimed, input));
        }

        // Nothing runnable: work is still to come from a node that has not finished, the run
        // is parked in front of a manual step waiting for the user, or the run is over.
        // finishIfSettled() decides which, and is also what closes a run out now that no lane
        // sits inside it to notice.
        graph.finishIfSettled(execution);
        return Optional.empty();
    }

    public int totalNodes(Long executionId) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).size();
    }
}
