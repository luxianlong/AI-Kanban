package com.kanban.workflow;

import com.kanban.worker.ClaudeSessionRunner;
import com.kanban.worker.SessionLimits;
import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * That two runs really do go at once — the claim {@link WorkflowDispatchTest} can't
 * make, because the interesting part is threads, not arithmetic.
 *
 * <p>The session runner is stubbed to block until <em>every</em> expected caller has
 * arrived — two for the pair below, and all four for the cap the board ships with.
 * That inverts the usual shape of a concurrency test: it can't pass by luck or by
 * timing, because a runner with fewer lanes never reaches the last call and the latch
 * simply never opens. This is the test that would have failed against the old tick,
 * which ran a node inline and did not come back until it was done.
 *
 * <p>Both halves are covered separately because they can break separately: runs of
 * <em>different</em> workflows overlapping only needs the tick not to block, while two
 * runs of the <em>same</em> workflow additionally needs nothing to be keyed on the
 * workflow — the scratch directory being the one that would bite, since two runs
 * sharing a directory would overwrite each other's files rather than fail loudly.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=true",
        "workflow.max-concurrent-executions=4",
        // Poll only when this test says so: the schedulers must not fire underneath it.
        "workflow.initial-delay-ms=3600000",
        "worker.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:workflowparalleldb;DB_CLOSE_DELAY=-1"
})
class WorkflowParallelExecutionTest {

    @Autowired
    WorkflowRunnerService service;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @MockBean
    ClaudeSessionRunner runner;

    /**
     * The context is shared across the methods below, so each starts from an empty
     * queue — a run left over from another test would otherwise take a lane and
     * there would be none free for the two this test is watching.
     */
    @BeforeEach
    void clearQueue() throws Exception {
        // Every lane has to be handed back first, or this test starts with fewer
        // free lanes than the runs it is about to watch.
        await(() -> service.lanesInUse() == 0, "A lane was still held from an earlier test.");
        nodeRuns.deleteAll();
        executions.deleteAll();
    }

    /** A one-node run, written straight to the repositories. */
    private Long queueRun(String workflowId, String nodeName) {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution(workflowId, "Research", "the subject"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", workflowId, nodeName, "do the thing", null, null, null),
                List.of(), 0));
        return execution.getId();
    }

    @Test
    void twoRunsOfTheSameWorkflowHaveASessionInFlightAtTheSameTime() throws Exception {
        Long first = queueRun("wf-a", "Collect");
        Long second = queueRun("wf-a", "Collect");

        Set<File> workDirs = ConcurrentHashMap.newKeySet();
        CountDownLatch bothArrived = arriveAndBlock(2, workDirs);

        service.poll();

        assertThat(bothArrived.await(10, TimeUnit.SECONDS))
                .as("two runs of one workflow should have a session open at the same time")
                .isTrue();

        // The property that makes the overlap safe rather than merely concurrent: a
        // scratch directory is per run, not per workflow, so the two sessions above
        // are not writing over each other.
        assertThat(workDirs).as("each run needs its own scratch directory").hasSize(2);

        settleAndAssertAllSucceeded(first, second);
    }

    @Test
    void runsOfDifferentWorkflowsHaveASessionInFlightAtTheSameTime() throws Exception {
        Long first = queueRun("wf-a", "Collect");
        Long second = queueRun("wf-b", "Draft");

        CountDownLatch bothArrived = arriveAndBlock(2, ConcurrentHashMap.newKeySet());

        service.poll();

        assertThat(bothArrived.await(10, TimeUnit.SECONDS))
                .as("runs of two workflows should have a session open at the same time")
                .isTrue();

        settleAndAssertAllSucceeded(first, second);
    }

    @Test
    void fourRunsHaveASessionInFlightAtTheSameTime() throws Exception {
        // The cap the board is configured for. Two of these runs share a workflow and
        // two do not, so nothing about the number four rests on either half alone.
        Long first = queueRun("wf-a", "Collect");
        Long second = queueRun("wf-a", "Collect");
        Long third = queueRun("wf-b", "Draft");
        Long fourth = queueRun("wf-c", "Draft");

        Set<File> workDirs = ConcurrentHashMap.newKeySet();
        CountDownLatch allArrived = arriveAndBlock(4, workDirs);

        service.poll();

        assertThat(allArrived.await(10, TimeUnit.SECONDS))
                .as("four runs should have a session open at the same time")
                .isTrue();

        // One scratch directory per run, not per workflow: with two of these runs
        // sharing wf-a, a directory keyed on the workflow would silently give three.
        assertThat(workDirs).as("each of the four runs needs its own scratch directory").hasSize(4);

        settleAndAssertAllSucceeded(first, second, third, fourth);
    }

    /**
     * Stub the runner so every caller waits until {@code arrivals} of them have
     * arrived, recording the directory each was handed. With fewer lanes than that the
     * last call never happens and the returned latch never opens — which is what makes
     * this test unable to pass by luck or by timing.
     */
    private CountDownLatch arriveAndBlock(int arrivals, Set<File> workDirs) throws Exception {
        CountDownLatch allArrived = new CountDownLatch(arrivals);
        when(runner.run(anyString(), anyString(), any(File.class), anyList(), any(SessionLimits.class))).thenAnswer(call -> {
            workDirs.add(call.getArgument(2));
            allArrived.countDown();
            allArrived.await(10, TimeUnit.SECONDS);
            return new ProcessResult(0, "done", false, 0.01, 5L, false);
        });
        return allArrived;
    }

    /**
     * A lane hands itself back after one node, so the runs are closed out on a later
     * tick rather than by the one that started them. Nothing is PENDING by then, so
     * those ticks launch no session — they only settle both runs.
     *
     * <p>Ticking inside the wait rather than once before it is what makes this
     * deterministic: the lane clears its claim just after writing the node's result,
     * so a single tick fired the moment the result appears can still find the run
     * claimed and dispatch nothing.
     */
    private void settleAndAssertAllSucceeded(Long... runs) throws Exception {
        for (Long run : runs) {
            awaitNodeSucceeded(run);
        }
        for (Long run : runs) {
            awaitExecutionTerminal(run);
            assertThat(executions.findById(run).orElseThrow().getStatus()).isEqualTo(RunState.SUCCEEDED);
        }
    }

    /**
     * A lane finishes its work after the tick has already returned, so the assertions
     * have to wait for it rather than read straight after {@code poll()}.
     */
    private void awaitNodeSucceeded(Long executionId) throws InterruptedException {
        await(() -> nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).get(0).getStatus()
                == RunState.SUCCEEDED, "Run #" + executionId + " never finished its node.");
    }

    private void awaitExecutionTerminal(Long executionId) throws InterruptedException {
        await(() -> {
            service.poll();
            return executions.findById(executionId).orElseThrow().getStatus().isTerminal();
        }, "Run #" + executionId + " never reached a terminal state.");
    }

    private void await(BooleanSupplier condition, String failure) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(failure);
    }
}
