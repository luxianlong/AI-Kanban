package com.kanban;

import com.kanban.agent.AgentService;
import com.kanban.agent.Lease;
import com.kanban.agent.LeaseReaper;
import com.kanban.agent.LeaseRepository;
import com.kanban.agent.LeaseState;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.WorkUnit;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when a worker simply stops answering.
 *
 * <p>This is the case that did not exist before the split. In-process, work was orphaned only
 * by the JVM dying, and the evidence was a row still IN_PROGRESS at the next startup. A remote
 * worker can vanish with nothing announcing it — a closed laptop, a dropped connection — so
 * the evidence becomes an unrenewed lease, and the recovery has to reach the same conclusions
 * {@code ClaudeWorkerService.requeueOrphans()} reaches: one more try, unless the task has
 * already had its attempts, in which case re-running it risks repeating work it has already
 * done.
 *
 * <p>The reaper is driven directly here rather than waited on, so the test does not depend on
 * a lease clock.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Work must actually be handed out here — worker.enabled is the board's master
        // switch for that now, not just a brake on a poller that no longer exists.
        "worker.enabled=true",
        // ...but nothing may shell out to the CLI: the story classifier is the one thing the
        // server still launches itself, and it rides on the same switch.
        "worker.classify-with-claude=false",
        "agent.token=test-worker-token",
        // Long enough that only the deliberate expiry below ever fires.
        "agent.lease-seconds=600",
        "worker.max-attempts=2",
        "spring.datasource.url=jdbc:h2:mem:leasereapertestdb;DB_CLOSE_DELAY=-1"
})
class LeaseReaperTest {

    @Autowired
    AgentService agent;

    @Autowired
    LeaseReaper reaper;

    @Autowired
    LeaseRepository leases;

    @Autowired
    TodoRepository todos;

    @BeforeEach
    void clean() {
        leases.deleteAll();
        todos.deleteAll();
    }

    /** Claim the one task on the board and then make its lease look abandoned. */
    private Lease claimThenGoSilent() {
        WorkUnit unit = agent.claim(new ClaimRequest("ghost-worker", List.of("general"), 1))
                .orElseThrow(() -> new AssertionError("expected work to be handed out"));
        Lease lease = leases.findById(unit.leaseId()).orElseThrow();
        lease.setExpiresAt(Instant.now().minusSeconds(1));
        return leases.save(lease);
    }

    private Todo pending(String title, int attemptsAlready) {
        Todo t = new Todo();
        t.setTitle(title);
        t.setAttempts(attemptsAlready);
        return todos.save(t);
    }

    @Test
    void aWorkerThatGoesSilentHasItsWorkRequeued() {
        Todo todo = pending("Research gold prices", 0);
        Lease lease = claimThenGoSilent();

        reaper.reapExpired();

        assertThat(todos.findById(todo.getId()).orElseThrow().getStatus())
                .isEqualTo(TodoStatus.PENDING);
        assertThat(leases.findById(lease.getId()).orElseThrow().getState())
                .isEqualTo(LeaseState.EXPIRED);
    }

    @Test
    void atTheAttemptCapItGoesToNeedsAttentionRatherThanBeingRunAgain() {
        // Already started once; claiming makes it twice, which is the cap.
        Todo todo = pending("Build the docs section", 1);
        claimThenGoSilent();

        reaper.reapExpired();

        Todo after = todos.findById(todo.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TodoStatus.FAILED);
        // The reason has to say why it is not simply being retried.
        assertThat(after.getResult()).contains("stopped reporting");
        assertThat(after.getResult()).contains("duplicate");
    }

    @Test
    void aFinishedTaskIsLeftAloneEvenIfItsLeaseExpires() {
        Todo todo = pending("Already answered", 0);
        Lease lease = claimThenGoSilent();
        // The worker got its answer in before the lease lapsed.
        Todo done = todos.findById(todo.getId()).orElseThrow();
        done.setStatus(TodoStatus.RESOLVED);
        done.setResult("the answer");
        todos.save(done);

        reaper.reapExpired();

        Todo after = todos.findById(todo.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TodoStatus.RESOLVED);
        assertThat(after.getResult()).isEqualTo("the answer");
        assertThat(leases.findById(lease.getId()).orElseThrow().getState())
                .isEqualTo(LeaseState.EXPIRED);
    }

    @Test
    void aWorkerSurfacingAfterItsLeaseWasReapedCannotWriteOntoTheTask() {
        pending("Contested job", 0);
        Lease lease = claimThenGoSilent();
        reaper.reapExpired();

        // The task is back on the queue and may already be running elsewhere. A late heartbeat
        // must tell this worker the lease is gone rather than quietly renewing it.
        var response = agent.heartbeat(lease.getId(),
                new com.kanban.protocol.HeartbeatRequest(9, List.of("still going")));
        assertThat(response.known()).isFalse();
    }
}
