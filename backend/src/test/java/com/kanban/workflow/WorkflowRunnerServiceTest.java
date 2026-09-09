package com.kanban.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            new WorkflowRunnerService(null, null, null, null, null, null, 1, true, true, true);

    private WorkflowNode node(String id, String name, String prompt) {
        return node(id, name, prompt, null);
    }

    private WorkflowNode node(String id, String name, String prompt, EffortLevel effort) {
        return new WorkflowNode(id, "wf", name, prompt, effort, null, null);
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
            new WorkflowRunnerService(null, null, null, null, null, null, 1, false, false, true);

    /** Everything off, including the fence — the only shape with no --settings at all. */
    private final WorkflowRunnerService unfenced =
            new WorkflowRunnerService(null, null, null, null, null, null, 1, false, false, false);

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
        assertThat(settings(service.sessionArgsFor(true, null)).path("ultracode").asBoolean()).isTrue();
        assertThat(settings(service.sessionArgsFor(false, null)).has("ultracode")).isFalse();
    }

    @Test
    void theSettingsArgIsTheFlagCarryingValidJson() {
        // In --print mode a settings value that fails to parse is ignored WITHOUT a word:
        // a malformed blob would not fail the build or log anything, it would silently
        // unfence every node and drop ultracode from the entry step.
        List<String> args = service.sessionArgsFor(true, null);

        assertThat(args).hasSize(2);
        assertThat(args.get(0)).isEqualTo("--settings");
        assertThat(settings(args).isObject()).isTrue();
    }

    @Test
    void bothEntryBoostsCanBeTurnedOffAndThenTheEntryStepIsJustAnotherStep() {
        NodeExecution entry = run(node("a", "Collect", "Find sources"), List.of(), 0);

        assertThat(settings(plain.sessionArgsFor(true, null)).has("ultracode")).isFalse();
        assertThat(plain.buildPrompt(entry, "gold prices in 2026", true))
                .isEqualTo("gold prices in 2026\n\nFind sources");
    }

    // --- a node cannot see outside its own directory ----------------------

    @Test
    void everyStepIsFencedIntoItsWorkingDirectoryNotJustTheEntryStep() {
        // The fence is the one setting that is NOT an entry-step boost: a step handed an
        // answer to improve has even less business reading the machine than the one that
        // has to go and find the material.
        for (boolean entry : new boolean[]{true, false}) {
            assertThat(settings(service.sessionArgsFor(entry, null))
                    .path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                    .as("entry=" + entry)
                    .isTrue();
        }
    }

    @Test
    void theFenceAndUltracodeTravelInOneSettingsBlobBecauseASecondOneWouldReplaceIt() {
        // --settings takes a single value, so two occurrences do not merge — the last
        // wins. Passing the fence as its own flag would quietly switch ultracode back
        // off on the one node that is supposed to have it.
        List<String> args = service.sessionArgsFor(true, null);

        assertThat(args).filteredOn("--settings"::equals).hasSize(1);
        JsonNode blob = settings(args);
        assertThat(blob.path("ultracode").asBoolean()).isTrue();
        assertThat(blob.path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                .isTrue();
    }

    @Test
    void theFenceCanBeTurnedOffAndThenAPlainStepGetsNoSettingsAtAll() {
        assertThat(unfenced.sessionArgsFor(false, null)).isEmpty();
        assertThat(unfenced.sessionArgsFor(true, null)).isEmpty();
    }

    @Test
    void eachStepOfARunGetsItsOwnDirectorySoItCannotReadASiblingsScratchFiles() {
        WorkflowExecution execution = executionFrom("a");
        NodeExecution first = withId(run(node("a", "Collect", ""), List.of(), 0), 11L);
        NodeExecution second = withId(run(node("b", "Summarise", ""), List.of("a"), 1), 12L);

        File a = WorkflowRunnerService.workDirFor(execution, first);
        File b = WorkflowRunnerService.workDirFor(execution, second);

        assertThat(a).isNotEqualTo(b);
        // Neither is inside the other, so the fence around one excludes the other.
        assertThat(b.toPath().startsWith(a.toPath())).isFalse();
        assertThat(a.toPath().startsWith(b.toPath())).isFalse();
        // Still grouped under the run, and still out of the repo.
        assertThat(a.getParentFile()).isEqualTo(b.getParentFile());
        assertThat(a.getPath()).startsWith(System.getProperty("java.io.tmpdir"));
    }

    @Test
    void aReRunOfOneStepLandsBackInItsOwnDirectory() {
        // Keyed on the node run's id, which a re-run reuses (it bumps attempt instead),
        // so a retried step sees its own leftovers and never a sibling's.
        WorkflowExecution execution = executionFrom("a");
        NodeExecution first = withId(run(node("a", "Collect", ""), List.of(), 0), 11L);
        File before = WorkflowRunnerService.workDirFor(execution, first);

        first.resetForRetry();

        assertThat(WorkflowRunnerService.workDirFor(execution, first)).isEqualTo(before);
    }

    /**
     * A node run with the id the database would have given it. {@code workDirFor} keys on
     * that id, and it is the one field a detached entity cannot be handed through a
     * constructor or a setter.
     */
    private NodeExecution withId(NodeExecution run, long id) {
        try {
            java.lang.reflect.Field field = NodeExecution.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(run, id);
            return run;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NodeExecution no longer has an 'id' field", e);
        }
    }

    /** The blob the CLI would parse out of {@code --settings}. */
    private JsonNode settings(List<String> args) {
        if (args.isEmpty()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return new ObjectMapper().readTree(args.get(args.indexOf("--settings") + 1));
        } catch (Exception e) {
            throw new AssertionError("--settings did not carry parseable JSON: " + args, e);
        }
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

    // --- a step's own effort level ---------------------------------------

    @Test
    void aStepWithNoLevelSetGetsNoEffortFlagSoTheCliDecides() {
        // Not "--effort medium" chosen as a stand-in for the default: the default is
        // the CLI's to move, and naming it here would pin every unset step to whatever
        // it happened to be on the day this was written.
        assertThat(service.sessionArgsFor(false, null)).doesNotContain("--effort");
        assertThat(service.sessionArgsFor(true, null)).doesNotContain("--effort");
    }

    @Test
    void aStepWithALevelIsStartedWithThatExactLevel() {
        for (EffortLevel level : EffortLevel.values()) {
            List<String> args = service.sessionArgsFor(false, level);

            assertThat(args).as("level=" + level).containsSequence("--effort", level.cliValue());
        }
    }

    @Test
    void theLevelIsSpeltTheWayTheCliSpellsItBecauseAWrongSpellingIsIgnoredSilently() {
        // The CLI takes an unknown --effort value, warns on stderr and runs at its
        // default. So a casing slip here would not fail anything — it would just
        // quietly undo the setting while the page kept showing it.
        assertThat(EffortLevel.XHIGH.cliValue()).isEqualTo("xhigh");
        assertThat(EffortLevel.allowed()).isEqualTo("low, medium, high, xhigh, max");
    }

    @Test
    void theEffortFlagIsItsOwnArgAndLeavesTheSettingsBlobAlone() {
        // --settings takes a single value; folding effort into that blob would mean
        // rebuilding it, and the fence and ultracode already ride in it.
        List<String> args = service.sessionArgsFor(true, EffortLevel.LOW);

        assertThat(args).filteredOn("--settings"::equals).hasSize(1);
        JsonNode blob = settings(args);
        assertThat(blob.path("ultracode").asBoolean()).isTrue();
        assertThat(blob.path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean()).isTrue();
        assertThat(blob.has("effort")).isFalse();
        assertThat(args).containsSequence("--effort", "low");
    }

    @Test
    void anEntryStepMayDisagreeWithUltracodeAboutHowHardToThink() {
        // Measured against the real CLI on one prompt: ultracode alone produced 348
        // output tokens, ultracode with --effort low produced 169, and --effort low
        // alone produced 170. The flag wins over the xhigh ultracode implies, and the
        // entry step keeps ultracode's other half.
        List<String> args = service.sessionArgsFor(true, EffortLevel.LOW);

        assertThat(settings(args).path("ultracode").asBoolean()).isTrue();
        assertThat(args).containsSequence("--effort", "low");
    }

    @Test
    void aStepsLevelIsSnapshottedIntoTheRunSoLaterEditsDoNotRewriteHistory() {
        WorkflowNode authored = node("a", "Collect", "Find sources", EffortLevel.MAX);
        NodeExecution recorded = run(authored, List.of(), 0);

        // The workflow is re-authored at a lower level; the run already made its copy.
        WorkflowNode reauthored = node("a", "Collect", "Find sources", EffortLevel.LOW);

        assertThat(recorded.getEffort()).isEqualTo(EffortLevel.MAX);
        assertThat(run(reauthored, List.of(), 0).getEffort()).isEqualTo(EffortLevel.LOW);
    }

    @Test
    void aRetriedStepStillThinksAtTheLevelTheRunRecorded() {
        NodeExecution recorded = run(node("a", "Collect", "Find sources", EffortLevel.XHIGH), List.of(), 0);

        recorded.resetForRetry();

        assertThat(recorded.getEffort()).isEqualTo(EffortLevel.XHIGH);
    }

    @Test
    void anUnknownLevelIsRefusedRatherThanQuietlyTreatedAsTheDefault() {
        assertThat(EffortLevel.parse(null)).isNull();
        assertThat(EffortLevel.parse("  ")).isNull();
        assertThat(EffortLevel.parse("XHigh")).isEqualTo(EffortLevel.XHIGH);
        assertThat(EffortLevel.parse(" max ")).isEqualTo(EffortLevel.MAX);

        assertThatThrownBy(() -> EffortLevel.parse("extreme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low, medium, high, xhigh, max");
        // "default" is how the picker labels the empty choice, not a level to send.
        assertThatThrownBy(() -> EffortLevel.parse("default"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
