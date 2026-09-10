package com.kanban.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kanban.worker.SessionLimits;
import com.kanban.worker.WorkerProperties;
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
 * preamble of its own. That holds for every node, the entry step included: which step
 * a run starts from changes nothing about how its session is launched.
 *
 * <p>{@code buildPrompt}/{@code assembleInput} touch none of the service's
 * collaborators, so the service is constructed with nulls.
 */
class WorkflowRunnerServiceTest {

    private final WorkflowRunnerService service = serviceWith(new WorkerProperties(), 3);

    /**
     * The service on a given worker configuration and ultracode multiplier.
     *
     * <p>{@code buildPrompt}/{@code assembleInput}/{@code sessionArgsFor} touch none of
     * the collaborators, so those stay null; {@code limitsFor} reads the properties and
     * nothing else.
     */
    private static WorkflowRunnerService serviceWith(WorkerProperties props, double multiplier) {
        return new WorkflowRunnerService(null, null, null, null, null, props, 1, true, multiplier);
    }

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

    // --- every step is launched the same way ------------------------------

    /** The fence off too — the only shape with no --settings at all. */
    private final WorkflowRunnerService unfenced =
            new WorkflowRunnerService(null, null, null, null, null, null, 1, false, 3);

    private WorkflowExecution executionFrom(String entryNodeId) {
        WorkflowExecution execution = execution("gold prices in 2026");
        execution.setEntryNodeId(entryNodeId);
        return execution;
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

    @Test
    void noStepIsStartedInUltracodeMode() {
        assertThat(settings(service.sessionArgsFor(null)).has("ultracode")).isFalse();
        assertThat(settings(service.sessionArgsFor(EffortLevel.LOW)).has("ultracode")).isFalse();
    }

    @Test
    void theSettingsArgIsTheFlagCarryingValidJson() {
        // In --print mode a settings value that fails to parse is ignored WITHOUT a word:
        // a malformed blob would not fail the build or log anything, it would silently
        // unfence every node.
        List<String> args = service.sessionArgsFor(null);

        assertThat(args).hasSize(2);
        assertThat(args.get(0)).isEqualTo("--settings");
        assertThat(settings(args).isObject()).isTrue();
    }

    // --- a node cannot see outside its own directory ----------------------

    @Test
    void everyStepIsFencedIntoItsWorkingDirectory() {
        // A step handed an answer to improve has even less business reading the machine
        // than the one that has to go and find the material, so the fence is on all of
        // them and takes no view of which step is the entry.
        assertThat(settings(service.sessionArgsFor(null))
                .path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                .isTrue();
        assertThat(settings(service.sessionArgsFor(EffortLevel.MAX))
                .path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                .isTrue();
    }

    @Test
    void theFenceTravelsInOneSettingsBlobBecauseASecondOneWouldReplaceIt() {
        // --settings takes a single value, so two occurrences do not merge — the last
        // wins. Anything else that ever becomes a setting has to join this blob rather
        // than arrive as a flag of its own.
        List<String> args = service.sessionArgsFor(null);

        assertThat(args).filteredOn("--settings"::equals).hasSize(1);
        assertThat(settings(args).path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean())
                .isTrue();
    }

    @Test
    void theFenceCanBeTurnedOffAndThenAStepGetsNoSettingsAtAll() {
        assertThat(unfenced.sessionArgsFor(null)).isEmpty();
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

    // --- a step's own effort level ---------------------------------------

    @Test
    void aStepWithNoLevelSetGetsNoEffortFlagSoTheCliDecides() {
        // Not "--effort medium" chosen as a stand-in for the default: the default is
        // the CLI's to move, and naming it here would pin every unset step to whatever
        // it happened to be on the day this was written.
        assertThat(service.sessionArgsFor(null)).doesNotContain("--effort");
    }

    @Test
    void aStepWithALevelIsStartedWithThatExactLevel() {
        for (EffortLevel level : EffortLevel.values()) {
            List<String> args = service.sessionArgsFor(level);

            assertThat(args).as("level=" + level).containsSequence("--effort", level.cliValue());
        }
    }

    @Test
    void theLevelIsSpeltTheWayTheCliSpellsItBecauseAWrongSpellingIsIgnoredSilently() {
        // The CLI takes an unknown --effort value, warns on stderr and runs at its
        // default. So a casing slip here would not fail anything — it would just
        // quietly undo the setting while the page kept showing it.
        assertThat(EffortLevel.XHIGH.cliValue()).isEqualTo("xhigh");
        assertThat(EffortLevel.allowed()).isEqualTo("low, medium, high, xhigh, max, ultracode");
    }

    @Test
    void ultracodeIsOfferedEvenThoughTheCliHelpTextLeavesItOut() {
        // `claude --help` prints "(low, medium, high, xhigh, max)" and so does the CLI's
        // own warning for an unknown value — both lists are incomplete, and building the
        // picker from them is exactly why this level was missing. Measured against the
        // real CLI: `--effort ultracode` emits no "Unknown --effort value" warning (an
        // unknown one does) and injects ~6,300 more cache-creation tokens than
        // `--effort max`, which is the orchestration schema being loaded.
        assertThat(EffortLevel.parse("ultracode")).isEqualTo(EffortLevel.ULTRACODE);
        assertThat(EffortLevel.parse("UltraCode")).isEqualTo(EffortLevel.ULTRACODE);
        assertThat(service.sessionArgsFor(EffortLevel.ULTRACODE))
                .containsSequence("--effort", "ultracode");
    }

    @Test
    void ultracodeIsListedLastSoItIsNeverConfusedForARungBelowMax() {
        // Not decoration: it rides the same enum as the five, and the only thing keeping
        // "max is the top of the thinking scale" true on the page is that ultracode sits
        // outside that run of levels rather than after it.
        EffortLevel[] levels = EffortLevel.values();

        assertThat(levels[levels.length - 1]).isEqualTo(EffortLevel.ULTRACODE);
        assertThat(levels[levels.length - 2]).isEqualTo(EffortLevel.MAX);
    }

    @Test
    void theEffortFlagIsItsOwnArgAndLeavesTheSettingsBlobAlone() {
        // --settings takes a single value; folding effort into that blob would mean
        // rebuilding it, and the fence already rides in it.
        List<String> args = service.sessionArgsFor(EffortLevel.LOW);

        assertThat(args).filteredOn("--settings"::equals).hasSize(1);
        JsonNode blob = settings(args);
        assertThat(blob.path("permissions").path(WorkflowRunnerService.FENCE_KEY).asBoolean()).isTrue();
        assertThat(blob.has("effort")).isFalse();
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

    // --- how long a step is allowed to take -----------------------------

    @Test
    void everyLevelButUltracodeRunsOnTheWorkersOwnClocks() {
        // Five of the six are the same shape of work thought about for longer, so one
        // ceiling fits them: a step at max is a step at low done more carefully, and
        // giving each level its own timeout would be five numbers to keep in step.
        WorkerProperties props = new WorkerProperties();
        WorkflowRunnerService service = serviceWith(props, 3);

        assertThat(service.limitsFor(null)).isEqualTo(props.sessionLimits());
        for (EffortLevel level : EffortLevel.values()) {
            if (level == EffortLevel.ULTRACODE) {
                continue;
            }
            assertThat(service.limitsFor(level)).as("level=" + level)
                    .isEqualTo(props.sessionLimits());
        }
    }

    @Test
    void anUltracodeStepIsGivenLongerBecauseItsRealWorkHappensInSubSessions() {
        // It is not a step that thinks harder, it is a step plus the sessions it starts
        // and then waits for. Measured against one step's ceiling it gets killed holding
        // nothing — after being billed for everything those sub-sessions did first.
        WorkerProperties props = new WorkerProperties();
        props.setTimeoutSeconds(3600);

        assertThat(serviceWith(props, 3).limitsFor(EffortLevel.ULTRACODE).timeoutSeconds())
                .isEqualTo(10_800);
    }

    @Test
    void theExtraTimeIsAMultipleOfWhateverTimeoutIsInForceRatherThanAFixedNumber() {
        // scripts/env.sh launches the live backend with --worker.timeout-seconds=3600,
        // overriding the 1800 in application.yml. A number of seconds written down here
        // would be right in one of those two worlds and wrong in the other — and could
        // end up SHORTER than an ordinary step's timeout, which is the opposite of the
        // point. This is the same lesson the background wait already learned.
        for (long timeout : new long[]{600, 1800, 3600, 7200}) {
            WorkerProperties props = new WorkerProperties();
            props.setTimeoutSeconds(timeout);
            WorkflowRunnerService service = serviceWith(props, 3);

            assertThat(service.limitsFor(EffortLevel.ULTRACODE).timeoutSeconds())
                    .as("ultracode against a %ss timeout", timeout)
                    .isEqualTo(timeout * 3)
                    .isGreaterThan(service.limitsFor(EffortLevel.MAX).timeoutSeconds());
        }
    }

    @Test
    void aStepGivenLongerToLiveMayWaitLongerForTheWorkItStarts() {
        // The half that would be silently useless on its own. An ultracode step alive for
        // three hours whose sub-sessions are killed at fifty-five minutes does not fail —
        // it reports the "I'll report back when it lands" sentence written before the
        // kill, and hands that down every edge below it.
        WorkerProperties props = new WorkerProperties();
        props.setTimeoutSeconds(3600);
        SessionLimits limits = serviceWith(props, 3).limitsFor(EffortLevel.ULTRACODE);

        assertThat(limits.backgroundWaitSeconds())
                .as("derived from this session's own ceiling, not the worker's")
                .isEqualTo(10_500)
                .isGreaterThan(props.getBackgroundWaitSeconds())
                // Still short of the ceiling, so the CLI can wind up and say why it
                // stopped instead of being force-killed with no cost and no reason.
                .isLessThan(limits.timeoutSeconds());
    }

    @Test
    void aWaitSomebodyPinnedIsStillTheWaitEvenForAnUltracodeStep() {
        // Deriving is the default, not the only option: worker.background-wait-seconds is
        // documented as pinning the wait regardless of the timeout, and 0 means "wait
        // indefinitely". Scaling a number an operator wrote down would be inventing one.
        WorkerProperties props = new WorkerProperties();
        props.setTimeoutSeconds(3600);
        props.setBackgroundWaitSeconds(900L);

        SessionLimits limits = serviceWith(props, 3).limitsFor(EffortLevel.ULTRACODE);

        assertThat(limits.timeoutSeconds()).isEqualTo(10_800);
        assertThat(limits.backgroundWaitSeconds()).isEqualTo(900);
    }

    @Test
    void aMultiplierOfOneTurnsTheWholeThingOffAndAnythingLowerIsClampedToIt() {
        // 1 is the off switch and has to leave ultracode on exactly the ordinary clocks.
        // Below 1 is a typo, and honouring it would give the step that fans out LESS time
        // than the step that does not — the one outcome this must never produce.
        WorkerProperties props = new WorkerProperties();

        assertThat(serviceWith(props, 1).limitsFor(EffortLevel.ULTRACODE))
                .isEqualTo(props.sessionLimits());
        assertThat(serviceWith(props, 0.5).limitsFor(EffortLevel.ULTRACODE))
                .isEqualTo(props.sessionLimits());
        assertThat(serviceWith(props, 0).limitsFor(EffortLevel.ULTRACODE))
                .isEqualTo(props.sessionLimits());
    }
}
