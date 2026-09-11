package com.kanban.workflow;

import com.kanban.work.WorkflowGraphProgress;
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
 * <p>The prompt is the user's node instructions and the upstream text, and nothing
 * else — see {@link WorkflowGraphProgress#buildPrompt} for why nothing adds a preamble of
 * its own. That holds for every node, the entry step included: which step a run starts
 * from changes nothing about how its session is launched.
 *
 * <p>These moved here with the code when execution left the server: the same assembly now
 * feeds a work unit handed to a worker on another machine, and the text is still the only
 * thing that crosses an edge. {@code buildPrompt}/{@code assembleInput} touch neither
 * repository, so both are passed as nulls.
 */
class WorkflowGraphProgressTest {

    private final WorkflowGraphProgress service = new WorkflowGraphProgress(null, null);

    private WorkflowNode node(String id, String name, String prompt) {
        return node(id, name, prompt, null);
    }

    private WorkflowNode node(String id, String name, String prompt, EffortLevel effort) {
        return new WorkflowNode(id, "wf", name, prompt, effort, false, null, null);
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
    void aHugeUpstreamOutputIsPassedOnWhole() {
        // A node that compares a draft against a rewrite needs both in full. Clipping
        // the longer one used to hand it a half-truncated article, which is exactly
        // when a node gives up on its input and goes looking for files instead.
        String article = "x".repeat(40_000);
        NodeExecution upstream = run(node("a", "Collect", ""), List.of(), 0);
        upstream.setOutput(article);
        NodeExecution downstream = run(node("b", "Summarise", ""), List.of("a"), 1);

        String input = service.assembleInput(execution(null), downstream, Map.of("a", upstream));

        assertThat(input).contains(article);
        assertThat(input).doesNotContain("truncated");
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
    void thePromptIsTheInputThenTheNodesOwnInstructionsAndNothingElse() {
        NodeExecution run = run(node("b", "Summarise", "Summarise the input in five bullets"),
                List.of("a"), 1);

        String prompt = service.buildPrompt(run, "some upstream text");

        assertThat(prompt).isEqualTo("some upstream text\n\nSummarise the input in five bullets");
    }

    @Test
    void thePromptCarriesNoScaffoldingOfItsOwn() {
        NodeExecution run = run(node("b", "Summarise", "Summarise the input"), List.of("a"), 1);

        String prompt = service.buildPrompt(run, "some upstream text");

        // Naming a directory made it the one filesystem landmark a fresh node had, so
        // nodes wrote their material there and later nodes read files instead of input.
        assertThat(prompt).doesNotContain("research");
        assertThat(prompt).doesNotContain("docs/");
        // The rest of the old preamble: identity, graph position, deploy guardrail,
        // failure sentinel. None of it is the user's instruction for this node.
        assertThat(prompt).doesNotContain("FRESH session");
        assertThat(prompt).doesNotContain("Research pipeline");
        assertThat(prompt).doesNotContain("step 2 of");
        assertThat(prompt).doesNotContain("scripts/deploy.sh");
        assertThat(prompt).doesNotContain("[[TASK_FAILED]]");
    }

    @Test
    void anEntryNodeStartedWithoutInputGetsJustItsInstructions() {
        NodeExecution run = run(node("a", "Collect", "Find sources"), List.of(), 0);

        assertThat(service.buildPrompt(run, "")).isEqualTo("Find sources");
    }

    @Test
    void aNodeWithNoInstructionsOfItsOwnGetsJustTheInput() {
        NodeExecution run = run(node("b", "Passthrough", ""), List.of("a"), 1);

        assertThat(service.buildPrompt(run, "upstream text")).isEqualTo("upstream text");
    }


    @Test
    void theEntryStepIsHandedItsPromptLikeAnyOtherStep() {
        // The entry step used to be launched differently — ultracode, plus a line asking
        // it to read the web through the chrome-devtools browser. That is gone: a step's
        // session is decided by what its author wrote for it, not by where it sits in
        // the graph.
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        assertThat(service.buildPrompt(entry, "gold prices in 2026"))
                .isEqualTo("gold prices in 2026\n\nFind sources");
        assertThat(service.buildPrompt(entry, ""))
                .doesNotContain("chrome-devtools")
                .doesNotContain("WebSearch")
                .doesNotContain("WebFetch");
    }
}
