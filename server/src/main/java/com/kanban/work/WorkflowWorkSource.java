package com.kanban.work;

import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the next workflow node a worker may run, and takes it.
 *
 * <p><strong>Independent branches of one run go at the same time.</strong> A graph that fans
 * out — {@code a → b, c, d} and then {@code b, c, d → e} — is saying those three steps do not
 * read each other, and the only reason to run them one after another was a scheduling
 * accident: the in-process runner gave each execution one lane of a thread pool, and when
 * execution moved to workers that lane was re-created here as an explicit "skip an execution
 * that already has a node RUNNING". Nothing in the graph rules needed it — a node has always
 * waited for its own dependencies and nothing else — so a fan-out of three cost three steps'
 * wall-clock for one step's worth of dependency. It now costs one, given three workers.
 *
 * <p>What still serialises is what the graph says serialises: {@code e} waits until
 * <em>every</em> one of {@code b}, {@code c}, {@code d} has SUCCEEDED, and reads their outputs
 * in the order its edges were snapshotted, not the order they happened to finish — so a run's
 * result does not depend on which branch won the race.
 *
 * <p><strong>Least-busy-first, then oldest-first</strong>, is what keeps the queue fair now
 * that one run can hold several workers at once. Oldest-first alone was fair while every run
 * took one worker; with a fan-out it would hand a ten-way branch every free worker on the
 * board before a run queued a second later got its first. Sorting by how many nodes a run
 * already has out fixes that without capping anything: a run uses every idle worker when
 * nobody else wants them, and yields the next one the moment somebody does. Runs that are
 * level are still served oldest-first, because the sort is stable and the query already
 * ordered them that way.
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
     * moves on, which is what lets several workers drain a graph-heavy board together. That
     * race is now also possible between two nodes <em>of the same run</em>; it is the same
     * race, settled the same way, by {@link NodeExecutionRepository#claimIfPending}.
     */
    public Optional<ClaimedNode> claimNext() {
        List<WorkflowExecution> active =
                executions.findByStatusInOrderByCreatedAtAsc(List.of(RunState.PENDING, RunState.RUNNING));
        if (active.isEmpty()) {
            return Optional.empty();
        }
        for (WorkflowExecution execution : leastBusyFirst(active)) {
            Optional<ClaimedNode> claimed = claimFrom(execution);
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    /**
     * The active runs, fewest nodes already in flight first.
     *
     * <p>{@code active} arrives oldest-first and {@link List#sort} is stable, so runs holding
     * the same number of workers keep that order — the fairness the queue had before
     * branches could overlap, now applied within each rung rather than across the whole list.
     */
    private List<WorkflowExecution> leastBusyFirst(List<WorkflowExecution> active) {
        Map<Long, Integer> inFlight = runningNodeCounts(active);
        List<WorkflowExecution> ordered = new ArrayList<>(active);
        ordered.sort(Comparator.comparingInt(e -> inFlight.getOrDefault(e.getId(), 0)));
        return ordered;
    }

    /** How many nodes each of these runs currently has out with a worker. */
    private Map<Long, Integer> runningNodeCounts(List<WorkflowExecution> active) {
        List<Long> ids = active.stream().map(WorkflowExecution::getId).toList();
        Map<Long, Integer> counts = new HashMap<>();
        for (NodeExecution run : nodeRuns.findByExecutionIdInAndStatusIn(ids, List.of(RunState.RUNNING))) {
            counts.merge(run.getExecutionId(), 1, Integer::sum);
        }
        return counts;
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
