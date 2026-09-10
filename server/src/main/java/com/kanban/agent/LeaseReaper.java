package com.kanban.agent;

import com.kanban.protocol.WorkKind;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.kanban.work.WorkflowGraphProgress;
import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecutionRepository;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.SessionLogService;
import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Takes work back from workers that stopped answering.
 *
 * <p>This is the remote counterpart of {@code ClaudeWorkerService.requeueOrphans()}, and it
 * applies deliberately the same policy, because the situation is the same one seen from the
 * other end. In-process, work was orphaned when the JVM died and the evidence was a row still
 * IN_PROGRESS at startup. With a worker on another machine there is no such moment — nothing
 * announces that a laptop went to sleep — so the evidence becomes a lease nobody renewed.
 *
 * <p>The policy is worth restating, since it is the one that keeps a task from being run twice:
 * a task below the max-attempts cap goes back to the queue for another try, and one that has
 * already been started {@link WorkerProperties#getMaxAttempts()} times is routed to Needs
 * Attention instead. Re-running it would repeat side effects it may already have had — files
 * committed, follow-up tasks filed — and a task that keeps being orphaned would otherwise keep
 * being re-run forever.
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final LeaseRepository leases;
    private final TodoRepository todos;
    private final LiveLogRegistry liveLog;
    private final SessionLogService sessionLogs;
    private final WorkerProperties workerProps;
    private final NodeExecutionRepository nodeRuns;
    private final WorkflowExecutionRepository executions;
    private final WorkflowGraphProgress graph;

    public LeaseReaper(LeaseRepository leases, TodoRepository todos, LiveLogRegistry liveLog,
                       SessionLogService sessionLogs,
                       WorkerProperties workerProps, NodeExecutionRepository nodeRuns,
                       WorkflowExecutionRepository executions, WorkflowGraphProgress graph) {
        this.leases = leases;
        this.todos = todos;
        this.liveLog = liveLog;
        this.sessionLogs = sessionLogs;
        this.workerProps = workerProps;
        this.nodeRuns = nodeRuns;
        this.executions = executions;
        this.graph = graph;
    }

    @Scheduled(fixedDelayString = "${agent.reap-interval-ms:15000}",
            initialDelayString = "${agent.reap-initial-delay-ms:20000}")
    @Transactional
    public void reapExpired() {
        List<Lease> expired = leases.findByStateAndExpiresAtBefore(LeaseState.ACTIVE, Instant.now());
        for (Lease lease : expired) {
            lease.setState(LeaseState.EXPIRED);
            leases.save(lease);
            if (lease.getKind() == WorkKind.TASK) {
                recoverTask(lease);
            } else {
                recoverNode(lease);
            }
        }
        if (!expired.isEmpty()) {
            log.warn("Reaped {} expired lease(s).", expired.size());
        }
    }

    /**
     * A node whose worker went silent is FAILED, never re-queued.
     *
     * <p>Deliberately not the task policy above. A task is a whole job and re-running it costs
     * a repeat; a node sits inside a graph, may already have had side effects, and re-running
     * it would duplicate them halfway through — with every step downstream then building on
     * whichever attempt happened to land. Failing it lets the ordinary "an upstream node did
     * not succeed" cascade close the run out, leaving the finished nodes' outputs readable and
     * the run in exactly the state Retry is for.
     */
    private void recoverNode(Lease lease) {
        NodeExecution run = nodeRuns.findById(lease.getRefId()).orElse(null);
        if (run == null || run.getStatus() != RunState.RUNNING) {
            return;
        }
        run.setStatus(RunState.FAILED);
        run.setError("Worker '" + lease.getWorkerName() + "' stopped reporting on this step. "
                + "Not re-run automatically, because it may already have done part of its work "
                + "— retry the run if you decide another attempt is safe.");
        run.setFinishedAt(Instant.now());
        liveLog.append(LiveLogRegistry.nodeKey(run.getId()),
                "✗ Worker '" + lease.getWorkerName() + "' stopped responding.");
        sessionLogs.keep(run);
        nodeRuns.save(run);
        executions.findById(run.getExecutionId()).ifPresent(graph::finishIfSettled);
        log.warn("Workflow node run #{} failed: worker '{}' stopped responding.",
                run.getId(), lease.getWorkerName());
    }

    private void recoverTask(Lease lease) {
        Todo todo = todos.findById(lease.getRefId()).orElse(null);
        if (todo == null || todo.getStatus() != TodoStatus.IN_PROGRESS) {
            // Already finished, deleted, or moved on by hand — nothing to take back.
            return;
        }
        String worker = lease.getWorkerName();
        if (todo.getAttempts() >= workerProps.getMaxAttempts()) {
            todo.setStatus(TodoStatus.FAILED);
            todo.setResult("Worker '" + worker + "' stopped reporting on this task after "
                    + todo.getAttempts() + " attempt(s) (the max-attempts cap). Not re-queued "
                    + "automatically, because a worker that vanishes mid-run may already have "
                    + "done part of the work — re-running would duplicate it. Review what it "
                    + "produced and re-queue manually if needed.");
            liveLog.append(todo.getId(),
                    "✗ Worker '" + worker + "' stopped responding; at the attempt cap, so this "
                            + "went to Needs Attention rather than being re-run.");
            sessionLogs.keep(todo);
            todos.save(todo);
            log.warn("Todo #{} routed to Needs Attention: worker '{}' went silent at the attempt cap.",
                    todo.getId(), worker);
        } else {
            todo.setStatus(TodoStatus.PENDING);
            liveLog.append(todo.getId(),
                    "■ Worker '" + worker + "' stopped responding; requeued for another worker.");
            sessionLogs.keep(todo);
            todos.save(todo);
            log.info("Todo #{} requeued: worker '{}' stopped responding.", todo.getId(), worker);
        }
    }
}
