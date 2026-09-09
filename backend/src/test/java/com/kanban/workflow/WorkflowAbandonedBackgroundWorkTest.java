package com.kanban.workflow;

import com.kanban.worker.ClaudeSessionRunner;
import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A step that hands its work to a dynamic workflow — which is what the {@code ultracode}
 * effort level is for — and whose session then runs out of patience waiting for it.
 *
 * <p>This happened on the board. A step ran for 12 minutes at ultracode, cost $2.47, and
 * finished {@code SUCCEEDED} holding 42 characters: <em>"the workflow is running in the
 * background, I'll produce the final piece when it's done"</em>. The CLI had killed that
 * workflow at its own 600s ceiling, so no such thing was ever coming.
 *
 * <p>The cost of recording that as a success is not one wasted step, it is the run: that
 * sentence is what crosses every edge below it, so the next three steps each answered
 * "there is no article here" and the whole execution still reported {@code SUCCEEDED}.
 * Which is what this pins — the step fails, and nothing downstream is handed a stand-in
 * to work on.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=true",
        // Poll only when this test says so: the schedulers must not fire underneath it.
        "workflow.initial-delay-ms=3600000",
        "worker.initial-delay-ms=3600000",
        "worker.background-wait-seconds=1500",
        "spring.datasource.url=jdbc:h2:mem:workflowabandoneddb;DB_CLOSE_DELAY=-1"
})
class WorkflowAbandonedBackgroundWorkTest {

    /** The sentence a step writes before the wait, and the one that used to be stored. */
    private static final String STAND_IN =
            "The workflow is running in the background; I'll hand you the finished piece when it lands.";

    @Autowired
    WorkflowRunnerService service;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @MockBean
    ClaudeSessionRunner runner;

    @BeforeEach
    void clearQueue() throws Exception {
        await(() -> service.lanesInUse() == 0, "A lane was still held from an earlier test.");
        nodeRuns.deleteAll();
        executions.deleteAll();
    }

    /** Draft, then polish what the draft produced. */
    private Long queueChain() {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-a", "Article", "write about gold"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-a", "Draft", "Write the draft",
                        EffortLevel.ULTRACODE, null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Polish", "Tighten the draft", null, null, null),
                List.of("a"), 1));
        return execution.getId();
    }

    private NodeExecution step(Long executionId, String name) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeName().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void aStepWhoseBackgroundWorkWasKilledDoesNotPassOffItsPromiseAsTheAnswer() throws Exception {
        // Exactly what the CLI reports in this case: exit 0, a real cost, and an answer
        // that was written before the work behind it was destroyed.
        when(runner.run(anyString(), anyString(), any(File.class), anyList()))
                .thenReturn(new ProcessResult(0, STAND_IN, false, 2.47, 139261L, true));
        Long run = queueChain();

        runUntilTerminal(run);

        NodeExecution draft = step(run, "Draft");
        assertThat(draft.getStatus()).isEqualTo(RunState.FAILED);
        assertThat(draft.getOutput()).as("a promise must not become the step's output").isNull();
        // The reason has to name what happened, since the step's own sentence sounds fine.
        assertThat(draft.getError())
                .contains("background work")
                .contains("1500s")
                .contains(STAND_IN);
        // Billed all the same — the session ran, and a failure that shows $0 hides that.
        assertThat(draft.getCostUsd()).isEqualTo(2.47);

        // The half that makes this worth failing on: nothing downstream is handed it.
        assertThat(step(run, "Polish").getStatus()).isEqualTo(RunState.SKIPPED);
        assertThat(executions.findById(run).orElseThrow().getStatus()).isEqualTo(RunState.FAILED);
    }

    @Test
    void aStepThatWaitedLongEnoughKeepsItsAnswer() throws Exception {
        // The control, and the case the raised ceiling is there to produce: the work
        // landed inside the wait, the session got another turn, and this is that turn.
        // The two runs differ in one boolean, so the guard cannot be passing on the text.
        when(runner.run(anyString(), anyString(), any(File.class), anyList()))
                .thenReturn(new ProcessResult(0, "Gold rose through 2026 because…", false,
                        2.47, 139261L, false));
        Long run = queueChain();

        runUntilTerminal(run);

        assertThat(step(run, "Draft").getStatus()).isEqualTo(RunState.SUCCEEDED);
        assertThat(step(run, "Draft").getOutput()).isEqualTo("Gold rose through 2026 because…");
        assertThat(step(run, "Polish").getStatus()).isEqualTo(RunState.SUCCEEDED);
        assertThat(executions.findById(run).orElseThrow().getStatus()).isEqualTo(RunState.SUCCEEDED);
    }

    /** Tick until the run closes out; a lane finishes after the tick has returned. */
    private void runUntilTerminal(Long executionId) throws InterruptedException {
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
