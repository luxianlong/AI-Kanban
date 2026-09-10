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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On startup, recover work that is marked as running but has nobody running it.
 *
 * <p>A server restart used to end every session, so anything IN_PROGRESS was by definition
 * orphaned and was recovered on the spot. That is no longer true and the change is a genuine
 * improvement: sessions live in worker processes, so a server restart now interrupts nothing —
 * leases are in the database, workers keep heartbeating through it, and their work survives.
 *
 * <p>What this sweep is for is the leftovers. A row marked busy with no ACTIVE lease behind it
 * has nobody working on it and nobody who will ever report on it: work that was in flight when
 * the board still ran its own sessions, a lease deleted by hand, a database restored from a
 * backup. Without this it would sit there looking busy forever.
 *
 * <p>Also clears the story-classifier holds, which do not survive a restart either — the pass
 * that set them is gone, so a task still flagged would be skipped by every worker for good.
 */
@Component
public class OrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweep.class);

    private final TodoRepository todos;
    private final NodeExecutionRepository nodeRuns;
    private final WorkflowExecutionRepository executions;
    private final LeaseRepository leases;
    private final LiveLogRegistry liveLog;
    private final SessionLogService sessionLogs;
    private final WorkerProperties workerProps;
    private final WorkflowGraphProgress graph;

    public OrphanSweep(TodoRepository todos, NodeExecutionRepository nodeRuns,
                       WorkflowExecutionRepository executions, LeaseRepository leases,
                       LiveLogRegistry liveLog, SessionLogService sessionLogs,
                       WorkerProperties workerProps,
                       WorkflowGraphProgress graph) {
        this.todos = todos;
        this.nodeRuns = nodeRuns;
        this.executions = executions;
        this.leases = leases;
        this.liveLog = liveLog;
        this.sessionLogs = sessionLogs;
        this.workerProps = workerProps;
        this.graph = graph;
    }

    @PostConstruct
    @Transactional
    public void sweep() {
        Set<Long> claimedTasks = new HashSet<>();
        Set<Long> claimedNodes = new HashSet<>();
        for (Lease lease : leases.findByState(LeaseState.ACTIVE)) {
            (lease.getKind() == WorkKind.TASK ? claimedTasks : claimedNodes).add(lease.getRefId());
        }

        int tasks = sweepTasks(claimedTasks);
        int nodes = sweepNodes(claimedNodes);
        int holds = clearClassifyHolds();

        if (tasks + nodes + holds > 0) {
            log.info("Startup sweep: recovered {} task(s) and {} workflow node(s) with no worker, "
                    + "and cleared {} stale classify hold(s).", tasks, nodes, holds);
        }
    }

    private int sweepTasks(Set<Long> claimed) {
        int recovered = 0;
        for (Todo todo : todos.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS)) {
            if (claimed.contains(todo.getId())) {
                continue; // a worker still holds this and is expected to report
            }
            if (todo.isDelegated()) {
                // IN_PROGRESS because an OUTSIDE worker claimed it through the public
                // delegated-task API. No lease of ours was ever involved and that worker is
                // very likely still on the job — leave the claim alone.
                continue;
            }
            if (todo.getAttempts() >= workerProps.getMaxAttempts()) {
                todo.setStatus(TodoStatus.FAILED);
                todo.setResult("Left running with no worker after " + todo.getAttempts()
                        + " attempt(s) (the max-attempts cap). Not re-queued automatically, "
                        + "because a run that was interrupted may already have done part of its "
                        + "work — re-running would duplicate it. Review the output and re-queue "
                        + "manually if needed.");
            } else {
                todo.setStatus(TodoStatus.PENDING);
            }
            todos.save(todo);
            recovered++;
        }
        return recovered;
    }

    /**
     * A node is failed rather than re-queued — the policy the workflow runner always had.
     * It may already have had side effects, and re-running it would duplicate them halfway
     * through a graph. Failing it lets the ordinary "an upstream node didn't succeed" cascade
     * close the run out, leaving the finished nodes' outputs readable and the run in exactly
     * the state Retry is for.
     */
    private int sweepNodes(Set<Long> claimed) {
        List<NodeExecution> running = nodeRuns.findByStatus(RunState.RUNNING);
        int recovered = 0;
        Set<Long> touched = new HashSet<>();
        for (NodeExecution run : running) {
            if (claimed.contains(run.getId())) {
                continue;
            }
            run.setStatus(RunState.FAILED);
            run.setError("Left running with no worker. Not re-run automatically, because this "
                    + "node may already have done part of its work — retry the run if you decide "
                    + "another attempt is safe.");
            run.setFinishedAt(Instant.now());
            liveLog.append(LiveLogRegistry.nodeKey(run.getId()),
                    "✗ Left running with no worker; failed on startup.");
            // This sweep runs at startup, so memory holds only the line just written — but
            // that is genuinely all this process knows, and saying so beats a blank box.
            sessionLogs.keep(run);
            nodeRuns.save(run);
            touched.add(run.getExecutionId());
            recovered++;
        }
        for (Long executionId : touched) {
            executions.findById(executionId).ifPresent(graph::finishIfSettled);
        }
        return recovered;
    }

    private int clearClassifyHolds() {
        List<Todo> held = todos.findByClassifyingTrue();
        for (Todo todo : held) {
            todo.setClassifying(false);
            todos.save(todo);
        }
        return held.size();
    }
}
