package com.kanban.workflow;

import com.kanban.agent.AgentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.WorkUnit;
import com.kanban.work.WorkflowGraphProgress;
import com.kanban.workflow.WorkflowDtos.ExecutionView;
import com.kanban.workflow.WorkflowDtos.NodeRunView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run that stops in front of a step and waits to be told to go.
 *
 * <p>Walked through the real path rather than over the rule alone, because the rule is only
 * half of it: the step must not be handed to a worker, <em>and</em> the run must not be
 * closed out while it is waiting. The second is the one that fails quietly. A run with
 * nothing runnable left is normally a run that is over, and the poller that closes those out
 * runs every few seconds — so a gate that only taught the work source to skip the step would
 * produce a pause that lasts until the next tick and is then recorded as a failure ("some
 * nodes never became runnable"), with the play button still sitting there on a finished run.
 *
 * <p>The claim path is the one a worker on another machine uses, so "was this step handed
 * out?" is asked the way a worker asks it.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=true",
        "worker.classify-with-claude=false",
        "agent.token=test-worker-token",
        // No pollers: this test drives the claim/report path itself, and a sweep landing
        // mid-assertion would be deciding the thing under test.
        "workflow.initial-delay-ms=3600000",
        "agent.reap-initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:manualstartdb;DB_CLOSE_DELAY=-1"
})
class WorkflowManualStartTest {

    @Autowired
    AgentService agent;

    @Autowired
    WorkflowService service;

    @Autowired
    WorkflowGraphProgress graph;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @BeforeEach
    void clearQueue() {
        nodeRuns.deleteAll();
        executions.deleteAll();
    }

    /** "Collect" → "Publish", where publishing waits to be told to go. */
    private Long queueGatedChain() {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-a", "Research", "gold prices in 2026"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-a", "Collect", "Find the sources", null, false, null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Publish", "Publish the summary", null, true, null, null),
                List.of("a"), 1));
        return execution.getId();
    }

    private Optional<WorkUnit> tryClaim() {
        return agent.claim(new ClaimRequest("test-worker", List.of("general", "workflow"), 1));
    }

    private WorkUnit claim() {
        return tryClaim().orElseThrow(() -> new AssertionError("expected a node to be handed out"));
    }

    private NodeExecution nodeNamed(Long executionId, String name) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeName().equals(name))
                .findFirst().orElseThrow();
    }

    private NodeRunView viewOf(Long executionId, String name) {
        return service.getExecution(executionId).nodes().stream()
                .filter(n -> n.nodeName().equals(name))
                .findFirst().orElseThrow();
    }

    private void finishFirstStep(Long executionId) {
        WorkUnit first = claim();
        assertThat(first.title()).contains("Collect");
        agent.report(first.leaseId(), new ResultRequest(0, false, false, false,
                "Three sources, all pointing the same way.", null, null));
    }

    // --- the pause -------------------------------------------------------

    @Test
    void aGatedStepIsNotHandedOutWhenTheStepBeforeItSucceeds() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);

        // The input is sitting there and the step is queued — and still nothing is handed out.
        assertThat(nodeNamed(executionId, "Collect").getStatus()).isEqualTo(RunState.SUCCEEDED);
        assertThat(tryClaim()).isEmpty();
        assertThat(nodeNamed(executionId, "Publish").getStatus()).isEqualTo(RunState.PENDING);
    }

    @Test
    void theRunStaysOpenWhileItWaitsRatherThanBeingRecordedAsStuck() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);

        // Everything that closes a run out, run against a run that is merely waiting: the
        // claim attempt above, and the sweep that exists for runs no result will arrive for.
        assertThat(tryClaim()).isEmpty();
        graph.finishIfSettled(executions.findById(executionId).orElseThrow());

        WorkflowExecution execution = executions.findById(executionId).orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(RunState.RUNNING);
        assertThat(execution.getError()).isNull();
        assertThat(execution.getFinishedAt()).isNull();
    }

    @Test
    void thePageIsToldWhichStepIsWaitingForIt() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);

        NodeRunView publish = viewOf(executionId, "Publish");
        assertThat(publish.manualStart()).isTrue();
        assertThat(publish.awaitingStart()).isTrue();
        assertThat(publish.startApprovedAt()).isNull();
        // And the step that has already run is not offering a button that does nothing.
        assertThat(viewOf(executionId, "Collect").awaitingStart()).isFalse();
    }

    @Test
    void aGatedStepDoesNotEvenAskUntilItsOwnInputIsReady() {
        Long executionId = queueGatedChain();
        claim(); // "Collect" is out with a worker; nothing has been produced yet

        NodeRunView publish = viewOf(executionId, "Publish");
        assertThat(publish.manualStart()).isTrue();
        assertThat(publish.awaitingStart()).isFalse();
    }

    // --- releasing it ----------------------------------------------------

    @Test
    void pressingStartHandsTheStepOutWithTheInputItWasWaitingOn() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);

        ExecutionView after = service.startNode(nodeNamed(executionId, "Publish").getId());

        assertThat(after.nodes().stream().filter(n -> n.nodeName().equals("Publish")).findFirst()
                .orElseThrow().awaitingStart()).isFalse();
        // Nothing is launched by releasing it — a worker takes it on its next ask, like
        // any other ready step.
        WorkUnit second = claim();
        assertThat(second.title()).contains("Publish");
        assertThat(second.prompt()).contains("Three sources, all pointing the same way.");
        assertThat(nodeNamed(executionId, "Publish").getStartApprovedAt()).isNotNull();
    }

    @Test
    void theRunCarriesOnAndClosesOutNormallyAfterwards() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);
        service.startNode(nodeNamed(executionId, "Publish").getId());

        WorkUnit second = claim();
        agent.report(second.leaseId(), new ResultRequest(0, false, false, false,
                "Published.", null, null));

        assertThat(executions.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(RunState.SUCCEEDED);
    }

    @Test
    void cancellingARunThatIsWaitingClosesItOut() {
        // The pause holds the run open indefinitely, so there has to be a way out of it
        // that is not "press the button you didn't want to press".
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);

        service.cancel(executionId);

        assertThat(nodeNamed(executionId, "Publish").getStatus()).isEqualTo(RunState.SKIPPED);
        assertThat(executions.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(RunState.CANCELLED);
    }

    @Test
    void aRunWhoseFirstStepIsManualDoesNotStartUntilItIsTold() {
        // The gate on the entry is the one place it changes what starting a run means:
        // the run is created, it is queued, and it does nothing. Worth holding to, because
        // "queue it now, run it when I have read the input" is a real use for it — and
        // because the run must not be closed out as having nothing to do.
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-b", "Gated entry", "gold prices"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        NodeExecution entry = nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-b", "Collect", "Find the sources", null, true, null, null),
                List.of(), 0));

        assertThat(tryClaim()).isEmpty();
        graph.finishIfSettled(executions.findById(execution.getId()).orElseThrow());
        assertThat(executions.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(RunState.PENDING);
        assertThat(viewOf(execution.getId(), "Collect").awaitingStart()).isTrue();

        service.startNode(entry.getId());

        WorkUnit first = claim();
        assertThat(first.title()).contains("Collect");
        // It still gets the run's own input, like any entry step.
        assertThat(first.prompt()).contains("gold prices");
    }

    // --- what may be released --------------------------------------------

    @Test
    void anOrdinaryStepCannotBeStartedByHandAndIsToldWhyNot() {
        Long executionId = queueGatedChain();

        assertThatThrownBy(() -> service.startNode(nodeNamed(executionId, "Collect").getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("doesn't wait to be started");
    }

    @Test
    void aGatedStepWhoseInputHasNotArrivedCannotBeStartedEarly() {
        Long executionId = queueGatedChain();

        assertThatThrownBy(() -> service.startNode(nodeNamed(executionId, "Publish").getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isn't waiting for you yet");
    }

    @Test
    void aStepCannotBeReleasedTwice() {
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);
        Long publishId = nodeNamed(executionId, "Publish").getId();
        service.startNode(publishId);

        assertThatThrownBy(() -> service.startNode(publishId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been started");
    }

    // --- retries ---------------------------------------------------------

    @Test
    void retryingAFailedGatedStepStartsItRatherThanAskingAgain() {
        // Pressing Retry is a person saying "run this". The reset takes the previous
        // go-ahead away, so without handing it back the button would appear to do nothing.
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);
        service.startNode(nodeNamed(executionId, "Publish").getId());
        WorkUnit second = claim();
        agent.report(second.leaseId(), new ResultRequest(1, false, false, false,
                "Publishing failed.", null, null));
        assertThat(nodeNamed(executionId, "Publish").getStatus()).isEqualTo(RunState.FAILED);

        service.retry(executionId);

        assertThat(nodeNamed(executionId, "Publish").getStartApprovedAt()).isNotNull();
        assertThat(claim().title()).contains("Publish");
    }

    @Test
    void reRunningFromAnEarlierStepMakesTheGatedStepAskAgain() {
        // The user pointed at "Collect", not at "Publish" — and by the time the run gets
        // back down to "Publish" there is a new answer above it to read first. That is the
        // whole reason the step is marked manual.
        Long executionId = queueGatedChain();
        finishFirstStep(executionId);
        service.startNode(nodeNamed(executionId, "Publish").getId());
        WorkUnit second = claim();
        agent.report(second.leaseId(), new ResultRequest(0, false, false, false,
                "Published.", null, null));

        service.retryNode(nodeNamed(executionId, "Collect").getId(), true);

        assertThat(nodeNamed(executionId, "Publish").getStartApprovedAt()).isNull();
        assertThat(claim().title()).contains("Collect");
        assertThat(tryClaim()).isEmpty();
    }
}
