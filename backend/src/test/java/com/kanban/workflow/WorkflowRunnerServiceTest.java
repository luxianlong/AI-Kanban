package com.kanban.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one workflow node's session actually receives.
 *
 * <p>This is the load-bearing part of the feature: because every node runs as a
 * fresh session with no shared history, the prompt is the <em>only</em> channel
 * between nodes. Assembling it wrong doesn't crash anything — it quietly produces a
 * node that answers without its input.
 *
 * <p>{@code buildPrompt}/{@code assembleInput} touch none of the service's
 * collaborators, so the service is constructed with nulls.
 */
class WorkflowRunnerServiceTest {

    private final WorkflowRunnerService service =
            new WorkflowRunnerService(null, null, null, null, null, null);

    private WorkflowNode node(String id, String name, String prompt) {
        return new WorkflowNode(id, "wf", name, prompt, null, null);
    }

    private NodeExecution run(WorkflowNode node, List<String> dependsOn, int order) {
        return new NodeExecution(1L, node, dependsOn, order);
    }

    private WorkflowExecution execution(String input) {
        return new WorkflowExecution("wf", "Research pipeline", input);
    }

    // --- what crosses an edge -------------------------------------------

    @Test
    void theEntryNodeGetsWhateverTheUserStartedTheRunWith() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        String input = service.assembleInput(execution("gold prices in 2026"), entry, Map.of());

        assertThat(input).isEqualTo("gold prices in 2026");
    }

    @Test
    void aDownstreamNodeGetsItsPredecessorsOutputLabelledByNode() {
        NodeExecution upstream = run(node("a", "Collect", "Find sources"), List.of(), 0);
        upstream.setOutput("three sources, all pointing the same way");
        NodeExecution downstream = run(node("b", "Summarise", "Summarise the input"), List.of("a"), 1);

        String input = service.assembleInput(execution(null), downstream, Map.of("a", upstream));

        assertThat(input).contains("Output of node \"Collect\"");
        assertThat(input).contains("three sources, all pointing the same way");
    }

    @Test
    void aNodeWithTwoParentsGetsBothOutputsAndCanTellThemApart() {
        NodeExecution bull = run(node("a", "Bull case", "Argue for"), List.of(), 0);
        bull.setOutput("BULL TEXT");
        NodeExecution bear = run(node("b", "Bear case", "Argue against"), List.of(), 1);
        bear.setOutput("BEAR TEXT");
        NodeExecution judge = run(node("c", "Verdict", "Weigh both"), List.of("a", "b"), 2);

        String input = service.assembleInput(execution(null), judge, Map.of("a", bull, "b", bear));

        assertThat(input).contains("\"Bull case\"").contains("BULL TEXT");
        assertThat(input).contains("\"Bear case\"").contains("BEAR TEXT");
        assertThat(input.indexOf("BULL TEXT")).isLessThan(input.indexOf("BEAR TEXT"));
    }

    @Test
    void aHugeUpstreamOutputIsTruncatedRatherThanPassedOnWhole() {
        NodeExecution upstream = run(node("a", "Collect", ""), List.of(), 0);
        upstream.setOutput("x".repeat(WorkflowRunnerService.INPUT_BUDGET_PER_DEPENDENCY + 5_000));
        NodeExecution downstream = run(node("b", "Summarise", ""), List.of("a"), 1);

        String input = service.assembleInput(execution(null), downstream, Map.of("a", upstream));

        assertThat(input).contains("…(truncated)");
        assertThat(input.length()).isLessThan(WorkflowRunnerService.INPUT_BUDGET_PER_DEPENDENCY + 500);
    }

    @Test
    void anUpstreamNodeThatProducedNothingSaysSoInsteadOfVanishing() {
        NodeExecution upstream = run(node("a", "Collect", ""), List.of(), 0);
        NodeExecution downstream = run(node("b", "Summarise", ""), List.of("a"), 1);

        assertThat(service.assembleInput(execution(null), downstream, Map.of("a", upstream)))
                .contains("produced no output");
    }

    // --- the prompt ------------------------------------------------------

    @Test
    void thePromptTellsTheNodeItHasNoSharedContextAndFeedsTheNextNode() {
        NodeExecution run = run(node("b", "Summarise", "Summarise the input in five bullets"),
                List.of("a"), 1);

        String prompt = service.buildPrompt(execution(null), run, "some upstream text", 3);

        // The two facts a node cannot work correctly without.
        assertThat(prompt).contains("FRESH session");
        assertThat(prompt).contains("passed on verbatim as the INPUT");
        // Its own identity and place in the graph.
        assertThat(prompt).contains("Research pipeline");
        assertThat(prompt).contains("\"Summarise\"");
        assertThat(prompt).contains("step 2 of 3");
        // The input, then the instructions last.
        assertThat(prompt).contains("some upstream text");
        assertThat(prompt).contains("Summarise the input in five bullets");
        assertThat(prompt.indexOf("some upstream text"))
                .isLessThan(prompt.indexOf("Summarise the input in five bullets"));
    }

    @Test
    void aFailingNodeIsToldHowToSaySo() {
        NodeExecution run = run(node("a", "Collect", "Find sources"), List.of(), 0);

        assertThat(service.buildPrompt(execution("topic"), run, "topic", 1))
                .contains("[[TASK_FAILED]]");
    }

    @Test
    void aNodeIsNotPointedAtTheDeployFlow() {
        NodeExecution run = run(node("a", "Collect", "Find sources"), List.of(), 0);

        String prompt = service.buildPrompt(execution("topic"), run, "topic", 1);

        // A workflow node produces output; it must not redeploy the app it runs inside.
        assertThat(prompt).contains("do NOT run");
        assertThat(prompt).contains("scripts/deploy.sh");
        assertThat(prompt).contains("restart the website");
    }

    @Test
    void anEntryNodeStartedWithoutInputIsToldThatPlainly() {
        NodeExecution run = run(node("a", "Collect", "Find sources"), List.of(), 0);

        String prompt = service.buildPrompt(execution(null), run, "", 1);

        assertThat(prompt).contains("ENTRY node");
        assertThat(prompt).contains("without any input");
    }
}
