package com.kanban.workflow;

import com.kanban.work.WorkflowGraphProgress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that holds a run up in front of a step until somebody says go.
 *
 * <p>Two things have to be true of it and they pull in opposite directions. A gated step
 * whose input is ready must <em>not</em> be handed out — that is the feature — and a run
 * parked in front of one must <em>not</em> be closed out as finished, because the one
 * place that decides "nothing left that could run" is also the place that decides a run
 * is over. Get the first right and not the second and the pause lasts until the next poll
 * tick, which then records the paused run as stuck.
 *
 * <p>The rule is a pure function of one row and its dependencies, so it is tested here
 * without a database; {@code WorkflowManualStartTest} walks the same pause through the
 * real claim/report path.
 */
class ManualStartGateTest {

    private final WorkflowGraphProgress graph = new WorkflowGraphProgress(null, null);

    private NodeExecution nodeRun(String id, String name, boolean manualStart, List<String> dependsOn) {
        return new NodeExecution(1L,
                new WorkflowNode(id, "wf", name, "do the thing", null, manualStart, null, null),
                dependsOn, dependsOn.isEmpty() ? 0 : 1);
    }

    /** "Collect" done, "Publish" queued behind it — the moment the gate is for. */
    private Map<String, NodeExecution> collectDone(NodeExecution publish) {
        NodeExecution collect = nodeRun("a", "Collect", false, List.of());
        collect.setStatus(RunState.SUCCEEDED);
        collect.setOutput("three sources");
        return Map.of("a", collect, "b", publish);
    }

    @Test
    void anOrdinaryStepGoesAsSoonAsItsInputIsThere() {
        NodeExecution publish = nodeRun("b", "Publish", false, List.of("a"));

        assertThat(graph.readyToStart(publish, collectDone(publish))).isTrue();
        assertThat(graph.awaitingManualStart(publish, collectDone(publish))).isFalse();
    }

    @Test
    void aManualStepIsNotHandedOutEvenWithItsInputReady() {
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));

        assertThat(graph.readyToStart(publish, collectDone(publish))).isFalse();
        assertThat(graph.awaitingManualStart(publish, collectDone(publish))).isTrue();
    }

    @Test
    void releasingItIsWhatMakesItRunnable() {
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));
        publish.approveStart();

        assertThat(graph.readyToStart(publish, collectDone(publish))).isTrue();
        // And it is no longer what holds the run open, or the run would never close.
        assertThat(graph.awaitingManualStart(publish, collectDone(publish))).isFalse();
        assertThat(publish.getStartApprovedAt()).isNotNull();
    }

    @Test
    void aManualStepWhoseInputHasNotArrivedIsWaitingForTheGraphAndNotForTheUser() {
        // The distinction the play button hangs off: offering it here would promise
        // something pressing it cannot deliver, since there is nothing to hand the step.
        NodeExecution collect = nodeRun("a", "Collect", false, List.of());
        collect.setStatus(RunState.RUNNING);
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));

        Map<String, NodeExecution> byNodeId = Map.of("a", collect, "b", publish);
        assertThat(graph.readyToStart(publish, byNodeId)).isFalse();
        assertThat(graph.awaitingManualStart(publish, byNodeId)).isFalse();
    }

    @Test
    void aManualStepBelowAFailureIsNotWaitingForAnybody() {
        // Nobody can usefully release it — its upstream has no output to hand on — so it
        // must not hold the run open. skipUnreachableWork marks it SKIPPED instead.
        NodeExecution collect = nodeRun("a", "Collect", false, List.of());
        collect.setStatus(RunState.FAILED);
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));

        Map<String, NodeExecution> byNodeId = Map.of("a", collect, "b", publish);
        assertThat(graph.awaitingManualStart(publish, byNodeId)).isFalse();
        assertThat(graph.readyToStart(publish, byNodeId)).isFalse();
    }

    @Test
    void aStepThatHasLeftTheQueueIsNotWaitingToBeStarted() {
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));
        publish.approveStart();
        publish.setStatus(RunState.RUNNING);

        assertThat(publish.isManualStartPending()).isFalse();
        assertThat(graph.readyToStart(publish, collectDone(publish))).isFalse();
    }

    @Test
    void aRetryArmsTheGateAgain() {
        // A go-ahead belongs to the attempt it was given for. The point of marking a step
        // manual is that somebody looks at the run before it goes, and the run as it stands
        // after a failure is not the run they looked at.
        NodeExecution publish = nodeRun("b", "Publish", true, List.of("a"));
        publish.approveStart();
        publish.setStatus(RunState.FAILED);

        publish.resetForRetry();

        assertThat(publish.getStartApprovedAt()).isNull();
        assertThat(publish.isManualStartPending()).isTrue();
        assertThat(graph.readyToStart(publish, collectDone(publish))).isFalse();
    }

    @Test
    void aRowWrittenBeforeTheFlagExistedRunsByItself() {
        // The columns are added to tables that already have rows, so a row saved before
        // this reads back null in both of them — which is the shape this builds. Null is
        // off: a graph that has never paused does not start pausing because of a migration.
        NodeExecution legacy = new NodeExecution();

        assertThat(legacy.isManualStart()).isFalse();
        assertThat(legacy.isManualStartPending()).isFalse();
        assertThat(graph.readyToStart(legacy, Map.of())).isTrue();
    }
}
