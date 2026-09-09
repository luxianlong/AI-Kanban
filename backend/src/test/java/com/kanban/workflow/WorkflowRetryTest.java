package com.kanban.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which steps a retry re-runs.
 *
 * <p>Getting this set wrong is expensive in both directions: too wide and a retry
 * pays again for sessions that already produced their answer, or starts a branch the
 * user never wired in; too narrow and the run is left half-finished with no way to
 * push it along. The rule is a pure function of the run's own snapshot, so it is
 * tested here without a database.
 */
class WorkflowRetryTest {

    /** A → B → C, plus an "orphan" node the entry cannot reach. */
    private List<NodeExecution> chainWithAnOrphan() {
        List<NodeExecution> runs = new ArrayList<>();
        runs.add(nodeRun("a", "Collect", List.of(), 0));
        runs.add(nodeRun("b", "Summarise", List.of("a"), 1));
        runs.add(nodeRun("c", "Publish", List.of("b"), 2));
        runs.add(nodeRun("orphan", "Draft idea", List.of(), 3));
        return runs;
    }

    private NodeExecution nodeRun(String id, String name, List<String> dependsOn, int order) {
        return new NodeExecution(1L, new WorkflowNode(id, "wf", name, "do the thing", null, null),
                dependsOn, order);
    }

    private NodeExecution find(List<NodeExecution> runs, String nodeId) {
        return runs.stream().filter(r -> r.getNodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private WorkflowExecution failedRun(String entryNodeId) {
        WorkflowExecution execution = new WorkflowExecution("wf", "Research", "gold prices");
        execution.setEntryNodeId(entryNodeId);
        execution.setStatus(RunState.FAILED);
        return execution;
    }

    private List<String> retriedIds(List<NodeExecution> runs, WorkflowExecution execution) {
        return WorkflowService.nodesToRetry(runs, execution).stream()
                .map(NodeExecution::getNodeId).toList();
    }

    private List<String> rerunIdsFrom(List<NodeExecution> runs, WorkflowExecution execution, String nodeId) {
        return WorkflowService.nodesToRerunFrom(runs, execution, nodeId).stream()
                .map(NodeExecution::getNodeId).toList();
    }

    /** A run of the chain where every wired step worked — the case single-step re-run is for. */
    private List<NodeExecution> successfulChain() {
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.SUCCEEDED);
        find(runs, "c").setStatus(RunState.SUCCEEDED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);
        return runs;
    }

    private WorkflowExecution succeededRun(String entryNodeId) {
        WorkflowExecution execution = failedRun(entryNodeId);
        execution.setStatus(RunState.SUCCEEDED);
        return execution;
    }

    // --- what a retry picks up -------------------------------------------

    @Test
    void aRetryTakesTheFailedStepAndWhateverWasBlockedBehindIt() {
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.FAILED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        assertThat(retriedIds(runs, failedRun("a"))).containsExactly("b", "c");
    }

    @Test
    void theStepThatAlreadySucceededIsLeftAloneWithItsOutput() {
        // The whole point of retrying rather than re-running: a workflow node is a
        // Claude session, so paying for "Collect" twice is a real cost, and its
        // stored output is exactly the input the failed step needs anyway.
        List<NodeExecution> runs = chainWithAnOrphan();
        NodeExecution collect = find(runs, "a");
        collect.setStatus(RunState.SUCCEEDED);
        collect.setOutput("three sources");
        find(runs, "b").setStatus(RunState.FAILED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        assertThat(retriedIds(runs, failedRun("a"))).doesNotContain("a");
        assertThat(collect.getOutput()).isEqualTo("three sources");
    }

    @Test
    void aStepTheEntryCouldNeverReachIsNotStartedByARetry() {
        // "orphan" has no dependencies, so resetting it to PENDING would make it look
        // perfectly runnable and the runner would start a session on a branch the
        // user had built but deliberately not wired in.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.FAILED);
        find(runs, "b").setStatus(RunState.SKIPPED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        assertThat(retriedIds(runs, failedRun("a"))).containsExactly("a", "b", "c");
    }

    @Test
    void aRunWhereEverythingWorkedHasNothingToRetry() {
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.SUCCEEDED);
        find(runs, "c").setStatus(RunState.SUCCEEDED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        WorkflowExecution execution = failedRun("a");
        execution.setStatus(RunState.SUCCEEDED);
        assertThat(WorkflowService.nodesToRetry(runs, execution)).isEmpty();
    }

    @Test
    void aRunStillInFlightOffersNothingToRetry() {
        // Retrying under a live session would reset the row the runner is about to
        // write to. The button is hidden on a live run for this reason; the service
        // answers 409 rather than trusting that.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.RUNNING);

        WorkflowExecution execution = failedRun("a");
        execution.setStatus(RunState.RUNNING);
        assertThat(WorkflowService.nodesToRetry(runs, execution)).isEmpty();
    }

    @Test
    void aCancelledRunPicksUpEveryStepItNeverGotTo() {
        // Cancelling closes out every queued node, including ones on a parallel branch
        // that had nothing to do with the step being killed. All of them are reachable,
        // so all of them come back.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.CANCELLED);
        find(runs, "b").setStatus(RunState.SKIPPED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        WorkflowExecution execution = failedRun("a");
        execution.setStatus(RunState.CANCELLED);
        assertThat(retriedIds(runs, execution)).containsExactly("a", "b", "c");
    }

    // --- runs recorded before the entry node was snapshotted --------------

    @Test
    void withoutASnapshottedEntryTheRetryFallsBackToTheConeBelowTheFailure() {
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.FAILED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        assertThat(retriedIds(runs, failedRun(null))).containsExactly("b", "c");
    }

    @Test
    void theFallbackStillWillNotStartAnUnwiredStep() {
        // The fallback is a subset of "reachable from the entry", never a superset:
        // anything downstream of a node the run reached is itself reachable. So the
        // orphan is out of reach of the fallback too, by construction.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.FAILED);
        find(runs, "b").setStatus(RunState.SKIPPED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);

        assertThat(retriedIds(runs, failedRun(null))).containsExactly("a", "b", "c");
    }

    // --- what resetting a node does --------------------------------------

    @Test
    void resettingANodeClearsTheAttemptButKeepsTheSnapshotAndTheBill() {
        NodeExecution run = nodeRun("b", "Summarise", List.of("a"), 1);
        run.setStatus(RunState.FAILED);
        run.setInput("three sources");
        run.setOutput("half an answer");
        run.setError("Timed out after 3600s.");
        run.setCostUsd(1.25);
        run.setDurationMs(90_000L);

        run.resetForRetry();

        assertThat(run.getStatus()).isEqualTo(RunState.PENDING);
        assertThat(run.getError()).isNull();
        assertThat(run.getOutput()).isNull();
        assertThat(run.getInput()).isNull();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getAttempt()).isEqualTo(2);
        // The snapshot is what makes the retry a retry of THIS run, not of whatever
        // the workflow says today.
        assertThat(run.getPrompt()).isEqualTo("do the thing");
        assertThat(run.dependencyIds()).containsExactly("a");
        // The failed attempt was billed; forgetting it would understate the run.
        assertThat(run.getCostUsd()).isEqualTo(1.25);
        assertThat(run.getDurationMs()).isEqualTo(90_000L);
    }

    @Test
    void aRowWrittenBeforeRetriesExistedCountsAsTheFirstAttempt() {
        assertThat(nodeRun("a", "Collect", List.of(), 0).getAttempt()).isEqualTo(1);
    }

    // --- re-running one step the user picked ------------------------------

    @Test
    void reRunningAStepTakesEverythingDownstreamOfIt() {
        // The point of the feature: "Summarise" succeeded, but on the wrong angle. The
        // fix is to redo it — and "Publish" read its output, so that has to go too.
        List<NodeExecution> runs = successfulChain();

        assertThat(rerunIdsFrom(runs, succeededRun("a"), "b")).containsExactly("b", "c");
    }

    @Test
    void reRunningAStepLeavesWhatFedItAlone() {
        // Upstream output is exactly the input the redone step needs, and it is what
        // makes this cheaper than starting the workflow over.
        List<NodeExecution> runs = successfulChain();
        find(runs, "a").setOutput("three sources");

        assertThat(rerunIdsFrom(runs, succeededRun("a"), "b")).doesNotContain("a");
        assertThat(find(runs, "a").getOutput()).isEqualTo("three sources");
    }

    @Test
    void reRunningTheLastStepIsJustThatStep() {
        assertThat(rerunIdsFrom(successfulChain(), succeededRun("a"), "c")).containsExactly("c");
    }

    @Test
    void reRunningTheEntryTakesTheWholeWiredGraphButNotTheUnwiredBranch() {
        assertThat(rerunIdsFrom(successfulChain(), succeededRun("a"), "a"))
                .containsExactly("a", "b", "c");
    }

    @Test
    void reRunningAForkTakesBothBranchesUnderIt() {
        // a → b → d and a → c → d. Redoing b invalidates d, which joins them; c is on
        // the other side of the fork and never read b's output, so it stays.
        List<NodeExecution> runs = new ArrayList<>();
        runs.add(nodeRun("a", "Collect", List.of(), 0));
        runs.add(nodeRun("b", "Angle one", List.of("a"), 1));
        runs.add(nodeRun("c", "Angle two", List.of("a"), 2));
        runs.add(nodeRun("d", "Merge", List.of("b", "c"), 3));
        runs.forEach(r -> r.setStatus(RunState.SUCCEEDED));

        assertThat(rerunIdsFrom(runs, succeededRun("a"), "b")).containsExactly("b", "d");
        assertThat(rerunIdsFrom(runs, succeededRun("a"), "a")).containsExactly("a", "b", "c", "d");
    }

    @Test
    void aStepTheEntryCouldNeverReachCannotBeStartedByHandEither() {
        // Same rule as a whole-run retry, for the same reason: the orphan has no
        // dependencies, so nothing but this check stops the runner starting a branch
        // the user built and deliberately left unwired.
        assertThat(WorkflowService.nodesToRerunFrom(successfulChain(), succeededRun("a"), "orphan"))
                .isEmpty();
    }

    @Test
    void noStepOfALiveRunCanBeReRun() {
        // It would reset the row the runner is about to write to.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.RUNNING);
        WorkflowExecution execution = failedRun("a");
        execution.setStatus(RunState.RUNNING);

        assertThat(WorkflowService.nodesToRerunFrom(runs, execution, "a")).isEmpty();
        assertThat(WorkflowService.rerunFromCounts(runs, execution)).isEmpty();
    }

    @Test
    void theCountOnEachStepIsWhatReRunningItWouldRedo() {
        // What the page puts on the button — same function that does the resetting, so
        // the number shown and the set acted on cannot drift apart.
        assertThat(WorkflowService.rerunFromCounts(successfulChain(), succeededRun("a")))
                .isEqualTo(Map.of("a", 3, "b", 2, "c", 1, "orphan", 0));
    }

    @Test
    void aFailedStepCanBeReRunOnItsOwnWithoutRetryingTheRest() {
        // Two independent branches broke; the user wants to push one of them along.
        List<NodeExecution> runs = new ArrayList<>();
        runs.add(nodeRun("a", "Collect", List.of(), 0));
        runs.add(nodeRun("b", "Angle one", List.of("a"), 1));
        runs.add(nodeRun("c", "Angle two", List.of("a"), 2));
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.FAILED);
        find(runs, "c").setStatus(RunState.FAILED);
        WorkflowExecution execution = failedRun("a");

        assertThat(rerunIdsFrom(runs, execution, "b")).containsExactly("b");
        // Whereas retrying the run takes both.
        assertThat(retriedIds(runs, execution)).containsExactly("b", "c");
    }

    @Test
    void withoutASnapshottedEntryAStepThatRanCanStillBeReRun() {
        List<NodeExecution> runs = successfulChain();

        assertThat(rerunIdsFrom(runs, succeededRun(null), "b")).containsExactly("b", "c");
    }

    @Test
    void withoutASnapshottedEntryASkippedStepIsRefused() {
        // SKIPPED means either "unwired" or "upstream didn't succeed", and an older run
        // can't tell them apart. Refusing is the safe half of the ambiguity — the whole
        // run is still retryable.
        List<NodeExecution> runs = chainWithAnOrphan();
        find(runs, "a").setStatus(RunState.SUCCEEDED);
        find(runs, "b").setStatus(RunState.FAILED);
        find(runs, "c").setStatus(RunState.SKIPPED);
        find(runs, "orphan").setStatus(RunState.SKIPPED);
        WorkflowExecution execution = failedRun(null);

        assertThat(WorkflowService.nodesToRerunFrom(runs, execution, "c")).isEmpty();
        assertThat(WorkflowService.nodesToRerunFrom(runs, execution, "orphan")).isEmpty();
        assertThat(rerunIdsFrom(runs, execution, "b")).containsExactly("b", "c");
    }
}
