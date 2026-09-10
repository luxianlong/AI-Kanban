package com.kanban.workflow;

import com.kanban.agent.AgentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.WorkUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a node's reported result does to the run it belongs to.
 *
 * <p>The case worth the most here is the quiet one: a step that handed its real work to
 * something running in the background — a dynamic workflow, which is exactly what the ultracode
 * effort level exists to start — and had that work killed when the CLI ran out of patience.
 * Every fact the worker reports says the session finished cleanly. What it finally said,
 * though, was written before the kill and is a promise to report back, not an answer.
 *
 * <p>Recording that as SUCCEEDED is the damage, and it is not confined to one step: a node's
 * output is what crosses every edge below it, so the steps downstream are handed a promise to
 * work from and say so in their own outputs. One abandoned step quietly spoils a whole run,
 * which is why the server reads the facts rather than trusting the exit code.
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
        "workflow.initial-delay-ms=3600000",
        "agent.reap-initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:workflowresultdb;DB_CLOSE_DELAY=-1"
})
class WorkflowNodeResultTest {

    @Autowired
    AgentService agent;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @BeforeEach
    void clearQueue() {
        nodeRuns.deleteAll();
        executions.deleteAll();
    }

    private Long queueChain() {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-a", "Research", "gold prices in 2026"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-a", "Collect", "Find the sources", null, null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Summarise", "Summarise the input", null, null, null),
                List.of("a"), 1));
        return execution.getId();
    }

    private WorkUnit claim() {
        return agent.claim(new ClaimRequest("test-worker", List.of("general", "workflow"), 1))
                .orElseThrow(() -> new AssertionError("expected a node to be handed out"));
    }

    private NodeExecution nodeNamed(Long executionId, String name) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeName().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void aStepWhoseBackgroundWorkWasKilledDoesNotPassOffItsPromiseAsTheAnswer() {
        Long executionId = queueChain();
        WorkUnit first = claim();

        // Exit code 0, no timeout: by every other signal this looks like a clean finish.
        agent.report(first.leaseId(), new ResultRequest(0, false, true, false,
                "I've started the workflow and will report back when it lands.", null, null));

        NodeExecution collect = nodeNamed(executionId, "Collect");
        assertThat(collect.getStatus()).isEqualTo(RunState.FAILED);
        assertThat(collect.getError()).contains("describes results it never received");
        // The promise must not be stored as the node's output, because that is what would
        // cross the edge.
        assertThat(collect.getOutput()).isNull();

        // And the branch below it is closed out rather than left queued forever.
        assertThat(nodeNamed(executionId, "Summarise").getStatus()).isEqualTo(RunState.SKIPPED);
        assertThat(executions.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(RunState.FAILED);
    }

    @Test
    void aStepThatWaitedLongEnoughKeepsItsAnswer() {
        Long executionId = queueChain();
        WorkUnit first = claim();

        agent.report(first.leaseId(), new ResultRequest(0, false, false, false,
                "three sources, all pointing the same way", 0.25, 42_000L));

        NodeExecution collect = nodeNamed(executionId, "Collect");
        assertThat(collect.getStatus()).isEqualTo(RunState.SUCCEEDED);
        assertThat(collect.getOutput()).isEqualTo("three sources, all pointing the same way");
        assertThat(collect.getCostUsd()).isEqualTo(0.25);

        // And the run carries on: the next step is now claimable and gets that text.
        WorkUnit second = claim();
        assertThat(second.title()).isEqualTo("Summarise");
        assertThat(second.prompt()).contains("three sources, all pointing the same way");
    }

    @Test
    void aRunIsClosedOutOnceItsLastStepReports() {
        Long executionId = queueChain();

        WorkUnit first = claim();
        agent.report(first.leaseId(), new ResultRequest(0, false, false, false, "sources", 0.1, null));
        WorkUnit second = claim();
        agent.report(second.leaseId(), new ResultRequest(0, false, false, false, "summary", 0.2, null));

        WorkflowExecution execution = executions.findById(executionId).orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(RunState.SUCCEEDED);
        assertThat(execution.getFinishedAt()).isNotNull();
        // Cost is the run's, totalled from its steps (offset because 0.1 + 0.2 is not 0.3).
        assertThat(execution.getCostUsd()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        // Nothing left to hand out.
        assertThat(agent.claim(new ClaimRequest("test-worker", List.of("general", "workflow"), 1)))
                .isEqualTo(Optional.empty());
    }

    @Test
    void oneRunNeverHasTwoStepsOutAtOnce() {
        queueChain();

        claim();
        // Its second step depends on the first, but even an independent branch would wait:
        // a run advances one step at a time, which is what lets the graph bookkeeping read a
        // run's rows as a whole.
        assertThat(agent.claim(new ClaimRequest("other-worker", List.of("general", "workflow"), 1)))
                .isEqualTo(Optional.empty());
    }
}
