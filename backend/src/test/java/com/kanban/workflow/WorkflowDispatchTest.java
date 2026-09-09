package com.kanban.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which runs a tick starts work on — the rule that makes executions parallel.
 *
 * <p>Two things it has to get right, and both are silent when wrong. Handing a lane
 * to a run that already has one would put two threads on the same node rows, and the
 * loser pays for a duplicate Claude session; never handing a second lane out at all
 * leaves the old strictly-serial behaviour in place while looking like it works.
 *
 * <p>{@link WorkflowRunnerService#dispatchable} is a pure function of the active list
 * and the in-flight set, so it is tested here without a database or a thread pool.
 */
class WorkflowDispatchTest {

    /** Executions carry no id until they are saved, so give the test rows one. */
    private WorkflowExecution execution(long id, String workflowId) {
        WorkflowExecution execution = new WorkflowExecution(workflowId, "Research", null);
        try {
            var field = WorkflowExecution.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(execution, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return execution;
    }

    private List<WorkflowExecution> queue(long... ids) {
        List<WorkflowExecution> active = new ArrayList<>();
        for (long id : ids) {
            active.add(execution(id, "wf"));
        }
        return active;
    }

    private Set<Long> inFlight(Long... ids) {
        Set<Long> set = ConcurrentHashMap.newKeySet();
        set.addAll(List.of(ids));
        return set;
    }

    // --- the point of the feature ---------------------------------------

    @Test
    void twoRunsOfTheSameWorkflowBothGetALane() {
        List<WorkflowExecution> active = List.of(execution(1, "wf"), execution(2, "wf"));

        assertThat(WorkflowRunnerService.dispatchable(active, inFlight(), 2))
                .containsExactly(1L, 2L);
    }

    @Test
    void runsOfDifferentWorkflowsBothGetALane() {
        List<WorkflowExecution> active = List.of(execution(1, "wf-a"), execution(2, "wf-b"));

        assertThat(WorkflowRunnerService.dispatchable(active, inFlight(), 2))
                .containsExactly(1L, 2L);
    }

    @Test
    void fourRunsGetALaneEachWhenFourAreConfigured() {
        // The board's shipped cap. Arithmetic, not threading — the overlap itself is
        // WorkflowParallelExecutionTest's job — but this is what the tick asks before
        // it hands anything to a lane.
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3, 4), inFlight(), 4))
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void aFifthRunWaitsForAFreeLane() {
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3, 4, 5), inFlight(), 4))
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3, 4, 5), inFlight(1L, 2L, 3L, 4L), 4))
                .isEmpty();
    }

    @Test
    void raisingTheCapOnlyEverAddsLanes() {
        // Two busy runs used to mean nothing else could start; at four they leave two
        // free. This is the whole behavioural difference the raise buys.
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3, 4), inFlight(1L, 2L), 2))
                .isEmpty();
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3, 4), inFlight(1L, 2L), 4))
                .containsExactly(3L, 4L);
    }

    // --- what stops it doubling up ---------------------------------------

    @Test
    void aRunThatAlreadyHasALaneIsNotGivenASecondOne() {
        // The load-bearing case: run 1 is mid-session, so this tick must skip it and
        // spend the free lane on run 2 instead. Handing it a second lane would put two
        // threads on one run's node rows and buy the same session twice.
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2), inFlight(1L), 2))
                .containsExactly(2L);
    }

    @Test
    void aBusyRunStillCountsAgainstTheCap() {
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3), inFlight(1L), 2))
                .containsExactly(2L);
    }

    @Test
    void nothingIsStartedWhenEveryLaneIsBusy() {
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3), inFlight(1L, 2L), 2))
                .isEmpty();
    }

    @Test
    void oneLaneRestoresTheOldStrictlySerialBehaviour() {
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3), inFlight(), 1))
                .containsExactly(1L);
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2, 3), inFlight(1L), 1))
                .isEmpty();
    }

    // --- fairness ---------------------------------------------------------

    @Test
    void theOldestWaitingRunsGoFirst() {
        // The active list arrives oldest-first, so a workflow started twice can't take
        // both lanes ahead of a run that has been queued longer.
        assertThat(WorkflowRunnerService.dispatchable(queue(4, 5, 6, 7), inFlight(), 2))
                .containsExactly(4L, 5L);
    }

    @Test
    void aRunThatFinishedItsNodeIsPickedStraightBackUp() {
        // A lane releases its claim between ticks; the next tick must find the run
        // again, or a multi-node graph would stall after its first node.
        assertThat(WorkflowRunnerService.dispatchable(queue(1, 2), inFlight(), 2))
                .contains(1L);
    }

    @Test
    void anEmptyQueueStartsNothing() {
        assertThat(WorkflowRunnerService.dispatchable(List.of(), inFlight(), 2)).isEmpty();
    }
}
