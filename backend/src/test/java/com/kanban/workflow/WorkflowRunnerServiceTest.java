package com.kanban.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * else — see {@link WorkflowRunnerService#buildPrompt} for why the runner adds no
 * preamble of its own. The one exception is the entry step, which is also the one step
 * started in ultracode mode; that whole difference is pinned at the bottom of this
 * file.
 *
 * <p>{@code buildPrompt}/{@code assembleInput} touch none of the service's
 * collaborators, so the service is constructed with nulls.
 */
class WorkflowRunnerServiceTest {

    private final WorkflowRunnerService service =
            new WorkflowRunnerService(null, null, null, null, null, null, 1, true, true);

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

        String prompt = service.buildPrompt(run, "some upstream text", false);

        assertThat(prompt).isEqualTo("some upstream text\n\nSummarise the input in five bullets");
    }

    @Test
    void thePromptCarriesNoScaffoldingOfItsOwn() {
        NodeExecution run = run(node("b", "Summarise", "Summarise the input"), List.of("a"), 1);

        String prompt = service.buildPrompt(run, "some upstream text", false);

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

        assertThat(service.buildPrompt(run, "", false)).isEqualTo("Find sources");
    }

    @Test
    void aNodeWithNoInstructionsOfItsOwnGetsJustTheInput() {
        NodeExecution run = run(node("b", "Passthrough", ""), List.of("a"), 1);

        assertThat(service.buildPrompt(run, "upstream text", false)).isEqualTo("upstream text");
    }

    // --- the entry step is run differently -------------------------------

    /** Both entry boosts off — what a workflow gets when the switches are flipped. */
    private final WorkflowRunnerService plain =
            new WorkflowRunnerService(null, null, null, null, null, null, 1, false, false);

    private WorkflowExecution executionFrom(String entryNodeId) {
        WorkflowExecution execution = execution("gold prices in 2026");
        execution.setEntryNodeId(entryNodeId);
        return execution;
    }

    @Test
    void theEntryStepIsAskedToReadTheWebThroughTheBrowserRatherThanFetchPages() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        String prompt = service.buildPrompt(entry, "gold prices in 2026", true);

        assertThat(prompt).contains("chrome-devtools");
        // Naming what it is preferred over is the half that changes the habit.
        assertThat(prompt).contains("WebSearch").contains("WebFetch");
    }

    @Test
    void theEntryStepsOwnInstructionStillHasTheLastWord() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        String prompt = service.buildPrompt(entry, "gold prices in 2026", true);

        assertThat(prompt.indexOf("chrome-devtools"))
                .isLessThan(prompt.indexOf("gold prices in 2026"));
        assertThat(prompt.indexOf("gold prices in 2026")).isLessThan(prompt.indexOf("Find sources"));
    }

    @Test
    void theBrowserLineNamesNoPlaceToPutAnything() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        // The reason the old preamble had to go: a filesystem landmark made nodes write
        // their material out instead of answering with it. A tool preference must not
        // smuggle one back in.
        assertThat(service.buildPrompt(entry, "", true))
                .doesNotContain("research")
                .doesNotContain("docs/")
                .doesNotContain("[[TASK_FAILED]]");
    }

    @Test
    void noStepBelowTheEntryIsToldAnythingAboutBrowsers() {
        NodeExecution downstream = run(node("b", "Summarise", "Summarise the input"), List.of("a"), 1);

        assertThat(service.buildPrompt(downstream, "some upstream text", false))
                .isEqualTo("some upstream text\n\nSummarise the input");
    }

    @Test
    void onlyTheEntryStepIsStartedInUltracodeMode() {
        assertThat(service.sessionArgsFor(true)).isEqualTo(WorkflowRunnerService.ULTRACODE_ARGS);
        assertThat(service.sessionArgsFor(false)).isEmpty();
    }

    @Test
    void theUltracodeArgIsTheSettingsFlagCarryingValidJson() throws Exception {
        // Hand-written JSON inside a Java string literal: a stray escape would not fail
        // the build, it would fail every entry step at runtime with a CLI parse error.
        List<String> args = WorkflowRunnerService.ULTRACODE_ARGS;

        assertThat(args).hasSize(2);
        assertThat(args.get(0)).isEqualTo("--settings");
        assertThat(new ObjectMapper().readTree(args.get(1)).path("ultracode").asBoolean()).isTrue();
    }

    @Test
    void bothEntryBoostsCanBeTurnedOffAndThenTheEntryStepIsJustAnotherStep() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        assertThat(plain.sessionArgsFor(true)).isEmpty();
        assertThat(plain.buildPrompt(entry, "gold prices in 2026", true))
                .isEqualTo("gold prices in 2026\n\nFind sources");
    }

    @Test
    void theEntryStepIsTheOneTheRunSnapshottedNotWhicheverCameFirst() {
        WorkflowExecution execution = executionFrom("b");
        NodeExecution first = run(node("a", "Collect", ""), List.of(), 0);
        NodeExecution second = run(node("b", "Also collect", ""), List.of(), 1);

        // Both have no dependencies, so position is not the question — which one the
        // run recorded as its entry is.
        assertThat(WorkflowRunnerService.isEntryNode(execution, first)).isFalse();
        assertThat(WorkflowRunnerService.isEntryNode(execution, second)).isTrue();
    }

    @Test
    void aRunRecordedBeforeTheEntryWasSnapshottedFallsBackToHavingNoUpstream() {
        WorkflowExecution old = execution("gold prices in 2026");
        NodeExecution entry = run(node("a", "Collect", ""), List.of(), 0);
        NodeExecution downstream = run(node("b", "Summarise", ""), List.of("a"), 1);

        assertThat(old.getEntryNodeId()).isNull();
        assertThat(WorkflowRunnerService.isEntryNode(old, entry)).isTrue();
        assertThat(WorkflowRunnerService.isEntryNode(old, downstream)).isFalse();
    }
}
