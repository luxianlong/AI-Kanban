package com.kanban.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
 * How a node's session is actually <em>started</em> — the claim
 * {@link WorkflowRunnerServiceTest} can't make on its own, because that one tests the
 * pure functions and would stay green against a {@code runNode} that computed the args
 * and the working directory and then handed the CLI something else.
 *
 * <p>So this drives a real two-step run through the real service with only the session
 * runner stubbed, and reads back the arguments and directory each session was launched
 * with. Two separate claims live here:
 *
 * <ul>
 *   <li><strong>What only the entry step gets</strong> — ultracode and the browser line.
 *       The downstream step is half the assertion, not padding: "the entry step gets
 *       ultracode" is only a feature if the other eight steps of a nine-step graph
 *       don't.</li>
 *   <li><strong>What every step gets</strong> — its own directory, and a session fenced
 *       into it. Here the downstream step is the other half for the opposite reason: a
 *       fence that stopped at the entry step would leave the fence unenforced on the
 *       steps most likely to go looking for a file.</li>
 * </ul>
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
    private final Map<String, File> workDirByNode = new ConcurrentHashMap<>();

    @BeforeEach
    void clearQueue() throws Exception {
        await(() -> service.lanesInUse() == 0, "A lane was still held from an earlier test.");
        nodeRuns.deleteAll();
        executions.deleteAll();
        argsByNode.clear();
        promptByNode.clear();
        workDirByNode.clear();
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
                new WorkflowNode("a", "wf-a", "Collect", "Find the sources", null, null, null),
                List.of(), 0));
        nodeRuns.save(new NodeExecution(execution.getId(),
                new WorkflowNode("b", "wf-a", "Summarise", "Summarise the input", null, null, null),
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
            workDirByNode.put(node, call.getArgument(2));
            argsByNode.put(node, call.getArgument(3));
            return new ProcessResult(0, "done", false, 0.01, 5L);
        });
    }

    @Test
    void theEntryStepIsLaunchedInUltracodeModeAndNoOtherStepIs() throws Exception {
        recordSessions();
        Long run = queueChain();

        runToCompletion(run);

        assertThat(settings("Collect").path(WorkflowRunnerService.ULTRACODE_KEY).asBoolean())
                .as("the entry step's session should carry the ultracode setting")
                .isTrue();
        assertThat(settings("Summarise").has(WorkflowRunnerService.ULTRACODE_KEY))
                .as("a step that is handed an answer has nothing to search for")
                .isFalse();
    }

    @Test
    void everyStepIsLaunchedFencedIntoItsWorkingDirectory() throws Exception {
        // The half WorkflowRunnerServiceTest cannot claim: it would stay green against a
        // runNode that built the fenced settings and then handed the CLI something else.
        recordSessions();
        Long run = queueChain();

        runToCompletion(run);

        for (String node : List.of("Collect", "Summarise")) {
            assertThat(settings(node).path("permissions")
                    .path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                    .as(node + "'s session should be fenced into its working directory")
                    .isTrue();
            assertThat(argsByNode.get(node)).filteredOn("--settings"::equals).hasSize(1);
        }
    }

    @Test
    void eachStepIsLaunchedInADirectoryOfItsOwn() throws Exception {
        // Without this the fence would draw its line around the whole run: both steps
        // would share one directory and each could read what the other left in it.
        recordSessions();
        Long run = queueChain();

        runToCompletion(run);

        File collect = workDirByNode.get("Collect");
        File summarise = workDirByNode.get("Summarise");
        assertThat(collect).isNotNull().isNotEqualTo(summarise);
        assertThat(summarise.toPath().startsWith(collect.toPath())).isFalse();
        assertThat(collect.toPath().startsWith(summarise.toPath())).isFalse();
        // Grouped under the run, and nowhere near the repo.
        assertThat(collect.getParentFile()).isEqualTo(summarise.getParentFile());
        assertThat(collect.getPath()).startsWith(System.getProperty("java.io.tmpdir"));
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

    /** The settings blob the CLI would parse out of the args a node was launched with. */
    private JsonNode settings(String node) throws Exception {
        List<String> args = argsByNode.get(node);
        assertThat(args).as("no session was recorded for " + node).isNotNull();
        int at = args.indexOf("--settings");
        return at < 0 ? JsonNodeFactory.instance.objectNode()
                : new ObjectMapper().readTree(args.get(at + 1));
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
