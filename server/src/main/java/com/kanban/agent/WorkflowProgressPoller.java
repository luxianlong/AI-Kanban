package com.kanban.agent;

import com.kanban.work.WorkflowGraphProgress;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Closes out workflow runs that have nothing left to do.
 *
 * <p>Almost always this has already happened: a run is settled the moment its last node's
 * result arrives, and that is handled there. This exists for the runs no result will ever
 * arrive for — one whose every node was skipped because the branch above it failed, one
 * cancelled while empty, one whose graph never resolved. The in-process runner noticed those
 * on its next tick because it ticked over every active run; with the work handed out on demand,
 * nothing would look at them again.
 *
 * <p>Cheap by construction: it only reads runs that are still open, and does nothing at all to
 * one that still has work to hand out.
 */
@Component
public class WorkflowProgressPoller {

    private final WorkflowExecutionRepository executions;
    private final WorkflowGraphProgress graph;

    public WorkflowProgressPoller(WorkflowExecutionRepository executions,
                                  WorkflowGraphProgress graph) {
        this.executions = executions;
        this.graph = graph;
    }

    @Scheduled(fixedDelayString = "${workflow.poll-interval-ms:5000}",
            initialDelayString = "${workflow.initial-delay-ms:12000}")
    @Transactional
    public void settleFinishedRuns() {
        List<WorkflowExecution> active =
                executions.findByStatusInOrderByCreatedAtAsc(List.of(RunState.PENDING, RunState.RUNNING));
        for (WorkflowExecution execution : active) {
            graph.finishIfSettled(execution);
        }
    }
}
