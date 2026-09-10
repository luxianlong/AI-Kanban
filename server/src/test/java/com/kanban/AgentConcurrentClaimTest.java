package com.kanban;

import com.kanban.agent.AgentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.WorkUnit;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two workers must never be handed the same task.
 *
 * <p>This is the invariant the split put at risk. While execution lived in this JVM, a single
 * scheduler thread picked the work and the impossibility was structural — {@code
 * WorkflowRunnerService} says as much in its own comments, and relies on it to avoid a version
 * column. Claims now arrive over HTTP on whatever Tomcat threads are free, so the guarantee has
 * to be made deliberately (the lock in {@link AgentService}), and the cost of getting it wrong
 * is not a stale read: it is two full Claude sessions, both billed, both doing the same job,
 * racing to write conflicting results onto one card.
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
        "spring.datasource.url=jdbc:h2:mem:agentracetestdb;DB_CLOSE_DELAY=-1"
})
class AgentConcurrentClaimTest {

    @Autowired
    AgentService agent;

    @Autowired
    TodoRepository todos;

    @Test
    void tenWorkersRacingForOneTaskProduceExactlyOneWinner() throws Exception {
        todos.deleteAll();
        Todo only = new Todo();
        only.setTitle("The only job on the board");
        todos.save(only);

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Callable<Optional<WorkUnit>>> attempts = java.util.stream.IntStream.range(0, racers)
                    .<Callable<Optional<WorkUnit>>>mapToObj(i -> () -> {
                        // Release them all at once, so the claims genuinely overlap rather than
                        // queueing behind thread startup.
                        startLine.await(5, TimeUnit.SECONDS);
                        return agent.claim(new ClaimRequest("worker-" + i, List.of("general"), 1));
                    })
                    .toList();

            List<Future<Optional<WorkUnit>>> futures = attempts.stream().map(pool::submit).toList();
            startLine.countDown();

            List<WorkUnit> won = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .flatMap(Optional::stream)
                    .toList();

            assertThat(won).hasSize(1);
            assertThat(won.get(0).refId()).isEqualTo(only.getId());
            // And distinct leases, had there been more work: the winner holds exactly one.
            assertThat(won.stream().map(WorkUnit::leaseId).collect(Collectors.toSet())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void racingWorkersShareOutTheQueueWithoutOverlapping() throws Exception {
        todos.deleteAll();
        int jobs = 5;
        for (int i = 0; i < jobs; i++) {
            Todo t = new Todo();
            t.setTitle("Job " + i);
            todos.save(t);
        }

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Future<Optional<WorkUnit>>> futures = java.util.stream.IntStream.range(0, racers)
                    .<Callable<Optional<WorkUnit>>>mapToObj(i -> () -> {
                        startLine.await(5, TimeUnit.SECONDS);
                        return agent.claim(new ClaimRequest("worker-" + i, List.of("general"), 1));
                    })
                    .map(pool::submit)
                    .toList();
            startLine.countDown();

            List<Long> claimed = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .flatMap(Optional::stream)
                    .map(WorkUnit::refId)
                    .toList();

            // Every job went out at most once, and no worker was handed a duplicate.
            assertThat(claimed).hasSize(jobs);
            assertThat(claimed).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }
}
