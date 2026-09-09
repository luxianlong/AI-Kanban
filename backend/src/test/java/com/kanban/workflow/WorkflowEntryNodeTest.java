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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * That the entry step is actually <em>started</em> differently — the claim
 * {@link WorkflowRunnerServiceTest} can't make on its own, because that one tests the
 * two pure functions and would stay green against a {@code runNode} that computed the
 * ultracode args and then never passed them to the CLI.
 *
 * <p>So this drives a real two-step run through the real service with only the session
 * runner stubbed, and reads back the arguments each session was launched with. The
 * downstream step is half the assertion, not padding: "the entry step gets ultracode"
 * is only a feature if the other eight steps of a nine-step graph don't.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=true",
        "workflow.entry-node-ultracode=true",
        "workflow.entry-node-prefer-browser=true",
        // Poll only when this test says so: the schedulers must not fire underneath it.
        "workflow.initial-delay-ms=3600000",
        "worker.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:workflowentrydb;DB_CLOSE_DELAY=-1"
})
class WorkflowEntryNodeTest {

    @Autowired
    WorkflowRunnerService service;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @MockBean
    ClaudeSessionRunner runner;

    /** What each node's session was launched with, keyed by the node's name. */
    private final Map<String, List<String>> argsByNode = new ConcurrentHashMap<>();
    private final Map<String, String> promptByNode = new ConcurrentHashMap<>();

    @BeforeEach
    void clearQueue() throws Exception {
        await(() -> service.lanesInUse() == 0, "A lane was still held from an earlier test.");
        nodeRuns.deleteAll();
        executions.deleteAll();
        argsByNode.clear();
        promptByNode.clear();
    }

    /**
     * A two-step chain — collect, then summarise — written straight to the
     * repositories, with the entry snapshotted as a real run has it.
     */
    private Long queueChain() {
        WorkflowExecution execution = executions.save(
                new WorkflowExecution("wf-a", "Research", "gold prices in 2026"));
        execution.setEntryNodeId("a");
        executions.save(execution);
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("a", "wf-a", "Collect", "Find the sources", null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Summarise", "Summarise the input", null, null),
                List.of("a"), 1));
        return execution.getId();
    }

    /**
     * Record how each session was launched. The node is identified by the prompt it was
     * handed, since that is what tells the two steps apart on the runner's own signature.
     */
    private void recordSessions() throws Exception {
        when(runner.run(anyString(), anyString(), any(File.class), anyList())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            String node = prompt.contains("Find the sources") ? "Collect" : "Summarise";
            promptByNode.put(node, prompt);
            argsByNode.put(node, call.getArgument(3));
            return new ProcessResult(0, "done", false, 0.01, 5L);
        });
    }

    @Test
    void theEntryStepIsLaunchedInUltracodeModeAndNoOtherStepIs() throws Exception {
        recordSessions();
        Long run = queueChain();

        runToCompletion(run);

        assertThat(argsByNode.get("Collect"))
                .as("the entry step's session should carry the ultracode settings flag")
                .containsExactlyElementsOf(WorkflowRunnerService.ULTRACODE_ARGS);
        assertThat(argsByNode.get("Summarise"))
                .as("a step that is handed an answer has nothing to search for")
                .isEmpty();
    }

    @Test
    void theEntryStepIsToldToBrowseAndNoOtherStepIs() throws Exception {
        recordSessions();
        Long run = queueChain();

        runToCompletion(run);

        assertThat(promptByNode.get("Collect")).contains("chrome-devtools");
        assertThat(promptByNode.get("Summarise")).doesNotContain("chrome-devtools");
        // Still the user's own text, with the run's input in front of it.
        assertThat(promptByNode.get("Collect"))
                .contains("gold prices in 2026")
                .endsWith("Find the sources");
    }

    /** Tick until the run closes out; a lane finishes after the tick has returned. */
    private void runToCompletion(Long executionId) throws InterruptedException {
        await(() -> {
            service.poll();
            return executions.findById(executionId).orElseThrow().getStatus().isTerminal();
        }, "Run #" + executionId + " never reached a terminal state.");
        assertThat(executions.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(RunState.SUCCEEDED);
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
