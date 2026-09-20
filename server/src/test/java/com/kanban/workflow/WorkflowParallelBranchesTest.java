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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fan-out runs at the same time, and a join still waits for all of it.
 *
 * <p>This is the whole of what "concurrent steps" means from the outside: claim the work
 * through the agent API the way real workers do, and see three branches out with three
 * workers at once. The rule it replaces was not in the graph — it was a line in the work
 * source that skipped any run with a node already RUNNING — so the test is written against
 * the hand-out and not against that method, and would stay honest if the scheduling moved
 * somewhere else again.
 *
 * <p>Nothing is stubbed and no CLI is involved: a claim describes work, a report says what
 * came back, and between those two the server is the only thing deciding anything.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=true",
        "worker.classify-with-claude=false",
        "agent.token=test-worker-token",
        // Nothing may advance underneath the test — the settle poller especially, which
        // would otherwise close runs out between two claims.
        "workflow.initial-delay-ms=3600000",
        "agent.reap-initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:workflowparalleldb;DB_CLOSE_DELAY=-1"
})
class WorkflowParallelBranchesTest {

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

    /**
     * The card's own topology: {@code a → b, c, d} and {@code b, c, d → e}. Written with b, c
     * and d adjacent in order index, because that is the arrangement in which a scheduler that
     * hands out "the first ready node" repeatedly could look right while still being serial.
     */
    private WorkflowExecution queueDiamond(String workflowId, String name, String input) {
        WorkflowExecution execution = executions.save(new WorkflowExecution(workflowId, name, input));
        execution.setEntryNodeId("a");
        executions.save(execution);
        node(execution, "a", "Split", List.of(), 0);
        node(execution, "b", "Branch B", List.of("a"), 1);
        node(execution, "c", "Branch C", List.of("a"), 2);
        node(execution, "d", "Branch D", List.of("a"), 3);
        node(execution, "e", "Join", List.of("b", "c", "d"), 4);
        return execution;
    }

    private NodeExecution node(WorkflowExecution execution, String nodeId, String name,
                               List<String> dependsOn, int orderIndex) {
        return nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode(nodeId, execution.getWorkflowId(), name, "Do " + name,
                        null, false, null, null),
                dependsOn, orderIndex));
    }

    private Optional<WorkUnit> tryClaim(String worker) {
        return agent.claim(new ClaimRequest(worker, List.of("general", "workflow"), 1));
    }

    private WorkUnit claim(String worker) {
        return tryClaim(worker).orElseThrow(() -> new AssertionError(
                "expected a node to be handed out to " + worker));
    }

    private void succeed(WorkUnit unit, String output) {
        agent.report(unit.leaseId(), new ResultRequest(0, false, false, false, output, null, null));
    }

    private List<String> runningNodeNames(Long executionId) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(r -> r.getStatus() == RunState.RUNNING)
                .map(NodeExecution::getNodeName)
                .toList();
    }

    @Test
    void threeIndependentBranchesAreOutWithThreeWorkersAtOnce() {
        WorkflowExecution execution = queueDiamond("wf-fan", "Fan out", "the brief");

        succeed(claim("w1"), "split three ways");

        // Three claims, nothing reported in between: before this change the second and third
        // came back empty, because the run already had a node RUNNING.
        List<String> handedOut = new ArrayList<>();
        handedOut.add(claim("w1").title());
        handedOut.add(claim("w2").title());
        handedOut.add(claim("w3").title());

        assertThat(handedOut)
                .as("every branch off the entry is claimable while its siblings are still going")
                .containsExactlyInAnyOrder("Branch B", "Branch C", "Branch D");
        assertThat(runningNodeNames(execution.getId()))
                .as("and all three are genuinely in flight at the same moment")
                .containsExactly("Branch B", "Branch C", "Branch D");
    }

    @Test
    void theJoinWaitsForEveryBranchAndReadsThemInEdgeOrder() {
        WorkflowExecution execution = queueDiamond("wf-join", "Fan in", "the brief");

        succeed(claim("w1"), "split three ways");
        WorkUnit b = claim("w1");
        WorkUnit c = claim("w2");
        WorkUnit d = claim("w3");

        assertThat(tryClaim("w4"))
                .as("the join is not runnable while its branches are")
                .isEmpty();

        // Finished in the order the race happened to settle, which is not the order the
        // edges were drawn in.
        succeed(d, "from D");
        assertThat(tryClaim("w4")).as("two branches still out").isEmpty();
        succeed(b, "from B");
        assertThat(tryClaim("w4")).as("one branch still out").isEmpty();
        succeed(c, "from C");

        WorkUnit join = claim("w4");
        assertThat(join.title()).isEqualTo("Join");
        // The point of the assertion: what the join reads is decided by the graph, not by
        // which branch finished first, so two runs of the same fan-out hand it the same text.
        assertThat(join.prompt()).isEqualTo(
                "— Output of node \"Branch B\":\nfrom B\n\n"
                        + "— Output of node \"Branch C\":\nfrom C\n\n"
                        + "— Output of node \"Branch D\":\nfrom D"
                        + "\n\nDo Join");

        succeed(join, "joined");
        assertThat(executions.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(RunState.SUCCEEDED);
    }

    @Test
    void aRunIsNotClosedOutWhileASiblingBranchIsStillGoing() {
        WorkflowExecution execution = queueDiamond("wf-open", "Fan out", "the brief");

        succeed(claim("w1"), "split three ways");
        WorkUnit b = claim("w1");
        claim("w2");
        claim("w3");

        // One branch comes back and nothing is claimable — the state that used to be
        // impossible, and the one in which "nothing to hand out" must not mean "finished".
        succeed(b, "from B");
        assertThat(tryClaim("w4")).isEmpty();
        assertThat(executions.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(RunState.RUNNING);
    }

    @Test
    void aFailedBranchStillPoisonsTheJoinWhileTheOthersFinish() {
        WorkflowExecution execution = queueDiamond("wf-fail", "Fan out", "the brief");

        succeed(claim("w1"), "split three ways");
        WorkUnit b = claim("w1");
        WorkUnit c = claim("w2");
        WorkUnit d = claim("w3");

        agent.report(c.leaseId(), new ResultRequest(1, false, false, false, "broke", null, null));
        // The run stays open: its other two branches are real work that is already paid for
        // and still running. Skipping them here would abandon sessions mid-flight.
        assertThat(executions.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(RunState.RUNNING);
        succeed(b, "from B");
        succeed(d, "from D");

        assertThat(tryClaim("w4")).as("the join can never run now").isEmpty();
        WorkflowExecution settled = executions.findById(execution.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(RunState.FAILED);
        assertThat(nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId()).stream()
                .filter(r -> r.getNodeId().equals("e")).findFirst().orElseThrow().getStatus())
                .isEqualTo(RunState.SKIPPED);
    }

    @Test
    void aFanOutYieldsTheNextWorkerToARunThatHasNone() {
        WorkflowExecution wide = queueDiamond("wf-wide", "Fan out", "the brief");
        WorkflowExecution narrow = executions.save(
                new WorkflowExecution("wf-narrow", "One step", "the other brief"));
        narrow.setEntryNodeId("only");
        executions.save(narrow);
        node(narrow, "only", "Only step", List.of(), 0);

        succeed(claim("w1"), "split three ways");   // the wide run's entry: it is the older run

        // Least-busy-first, and both runs are idle, so the older one goes first.
        assertThat(claim("w1").title()).isIn("Branch B", "Branch C", "Branch D");
        // Now the wide run holds a worker and the narrow one holds none — oldest-first alone
        // would hand it the whole fan-out before the newer run ran anything at all.
        assertThat(claim("w2").title())
                .as("a run with nothing out is served before a run that is already busy")
                .isEqualTo("Only step");
        // Level again at one apiece, so the tie goes back to the older run.
        assertThat(claim("w3").title()).isIn("Branch B", "Branch C", "Branch D");

        assertThat(runningNodeNames(wide.getId())).hasSize(2);
        assertThat(runningNodeNames(narrow.getId())).containsExactly("Only step");
    }
}
