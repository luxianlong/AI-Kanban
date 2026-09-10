package com.kanban.agent;

import com.kanban.protocol.WorkKind;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.kanban.worker.LiveLogRegistry;
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
    private final WorkerProperties workerProps;

    public LeaseReaper(LeaseRepository leases, TodoRepository todos, LiveLogRegistry liveLog,
                       WorkerProperties workerProps) {
        this.leases = leases;
        this.todos = todos;
        this.liveLog = liveLog;
        this.workerProps = workerProps;
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
            }
        }
        if (!expired.isEmpty()) {
            log.warn("Reaped {} expired lease(s).", expired.size());
        }
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
            todos.save(todo);
            liveLog.append(todo.getId(),
                    "✗ Worker '" + worker + "' stopped responding; at the attempt cap, so this "
                            + "went to Needs Attention rather than being re-run.");
            log.warn("Todo #{} routed to Needs Attention: worker '{}' went silent at the attempt cap.",
                    todo.getId(), worker);
        } else {
            todo.setStatus(TodoStatus.PENDING);
            todos.save(todo);
            liveLog.append(todo.getId(),
                    "■ Worker '" + worker + "' stopped responding; requeued for another worker.");
            log.info("Todo #{} requeued: worker '{}' stopped responding.", todo.getId(), worker);
        }
    }
}
