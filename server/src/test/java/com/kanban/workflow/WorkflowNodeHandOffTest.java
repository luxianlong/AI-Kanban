package com.kanban.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.agent.AgentService;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.WorkDirMode;
import com.kanban.protocol.WorkUnit;
import com.kanban.work.NodeSessionPlanner;
import com.kanban.worker.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a node's session is actually <em>handed out</em> — the claim the unit tests can't make
 * on their own, because they would stay green against a hand-off that computed the args and
 * the directory correctly and then put something else in the work unit.
 *
 * <p>So this queues a real two-step run and claims each step through the agent API, reading
 * back exactly what a worker would be told to do. What it pins is that <em>every</em> step is
 * handed out the same way: a directory of its own, a session fenced into it, and nothing
 * prepended to the prompt its author wrote. Both steps are asserted on, because the entry step
 * is the one that used to be launched differently and the downstream step is the one a fence
 * stopping at the entry would leave unenforced.
 *
 * <p>No CLI is stubbed, because none is involved any more: the server's whole job here is to
 * describe the work, and this is that description.
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
        // Nothing must advance underneath the test.
        "workflow.initial-delay-ms=3600000",
        "agent.reap-initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:workflowhandoffdb;DB_CLOSE_DELAY=-1"
})
class WorkflowNodeHandOffTest {

    @Autowired
    AgentService agent;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @Autowired
    WorkerProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearQueue() {
        nodeRuns.deleteAll();
        executions.deleteAll();
    }

    /** A two-step chain — collect, then summarise — with the entry snapshotted as a real run has it. */
    private Long queueChain(EffortLevel entryEffort) {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-a", "Research", "gold prices in 2026"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-a", "Collect", "Find the sources", entryEffort, false, null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Summarise", "Summarise the input", null, false, null, null),
                List.of("a"), 1));
        return execution.getId();
    }

    private WorkUnit claim() {
        return agent.claim(new ClaimRequest("test-worker", List.of("general", "workflow"), 1))
                .orElseThrow(() -> new AssertionError("expected a node to be handed out"));
    }

    /** Finish the node this lease is for, so the next one becomes claimable. */
    private void succeed(WorkUnit unit, String output) {
        agent.report(unit.leaseId(), new com.kanban.protocol.ResultRequest(
                0, false, false, false, output, null, null));
    }

    private JsonNode settings(List<String> args) throws Exception {
        int i = args.indexOf("--settings");
        assertThat(i).as("a --settings arg is present").isGreaterThanOrEqualTo(0);
        return mapper.readTree(args.get(i + 1));
    }

    @Test
    void everyStepIsHandedOutFencedIntoItsOwnDirectory() throws Exception {
        queueChain(null);

        WorkUnit first = claim();
        assertThat(first.title()).isEqualTo("Collect");
        assertThat(settings(first.sessionArgs())
                .path("permissions").path(NodeSessionPlanner.FENCE_KEY).asBoolean()).isTrue();
        assertThat(first.workDirMode())
                .as("a node transforms text and never gets the repo")
                .isEqualTo(WorkDirMode.SCRATCH);

        succeed(first, "three sources");

        WorkUnit second = claim();
        assertThat(second.title()).isEqualTo("Summarise");
        assertThat(settings(second.sessionArgs())
                .path("permissions").path(NodeSessionPlanner.FENCE_KEY).asBoolean())
                .as("the step after the entry is fenced too")
                .isTrue();
        assertThat(second.workDirKey())
                .as("each step gets a directory of its own, so it cannot read a sibling's leftovers")
                .isNotEqualTo(first.workDirKey());
    }

    @Test
    void noStepIsToldAnythingItsAuthorDidNotWrite() {
        queueChain(null);

        WorkUnit first = claim();
        // The entry step gets the run's input and its own instructions. Nothing else: no
        // preamble, no sentinel, no directory hint. Everything the runner used to prepend was
        // the only filesystem landmark a node ever saw, and nodes acted on it.
        assertThat(first.prompt()).isEqualTo("gold prices in 2026\n\nFind the sources");

        succeed(first, "three sources, all pointing the same way");

        WorkUnit second = claim();
        assertThat(second.prompt()).isEqualTo(
                "— Output of node \"Collect\":\nthree sources, all pointing the same way"
                        + "\n\nSummarise the input");
    }

    @Test
    void anUltracodeStepIsHandedLongerClocksThanTheStepBesideIt() {
        queueChain(EffortLevel.ULTRACODE);

        WorkUnit ultracode = claim();
        assertThat(ultracode.sessionArgs()).containsSequence("--effort", "ultracode");
        assertThat(ultracode.limits().timeoutSeconds())
                .as("an ultracode step is a step plus the sub-sessions it starts")
                .isGreaterThan(props.getTimeoutSeconds());

        succeed(ultracode, "done");

        WorkUnit ordinary = claim();
        assertThat(ordinary.limits().timeoutSeconds()).isEqualTo(props.getTimeoutSeconds());
        assertThat(ultracode.limits().timeoutSeconds())
                .isGreaterThan(ordinary.limits().timeoutSeconds());
    }

    @Test
    void theLongerStepMayAlsoWaitLongerForTheSubSessionsItStarts() {
        queueChain(EffortLevel.ULTRACODE);

        WorkUnit ultracode = claim();

        // The half that fails silently if forgotten: the step stays alive, its background work
        // is killed under it, and what gets stored and passed downstream is the sentence it
        // wrote before the kill.
        assertThat(ultracode.limits().backgroundWaitSeconds())
                .isGreaterThan(props.getBackgroundWaitSeconds())
                .isLessThan(ultracode.limits().timeoutSeconds());
    }

    @Test
    void aStepWithNoLevelIsHandedNoEffortFlagSoTheCliDecides() {
        queueChain(null);

        assertThat(claim().sessionArgs()).doesNotContain("--effort");
    }
}
