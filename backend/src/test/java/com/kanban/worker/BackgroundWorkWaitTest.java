package com.kanban.worker;

import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a session that hands its real work to something running in the
 * background — a dynamic workflow, a background sub-session — and then ends its turn.
 *
 * <p>The CLI waits for that work before exiting, and if it lands the session gets a
 * fresh turn and writes a real answer. If the wait runs out, the CLI kills the work and
 * reports the sentence the session wrote <em>before</em> the wait, which is a promise to
 * report back. Nothing in the terminal {@code result} event distinguishes the two: the
 * exit code is 0 and the run looks clean.
 *
 * <p>So both halves are pinned here — the ceiling the CLI is given, and the fact that
 * hitting it is detected — against a stub CLI rather than mocks, because the whole point
 * is what a real process is handed and what comes back over its stdout.
 */
class BackgroundWorkWaitTest {

    /** The line the CLI prints, verbatim, when it stops waiting and kills the work. */
    private static final String CEILING_HIT =
            "Background tasks still running after 1500s; terminating."
                    + " Set CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0 to wait indefinitely.";

    /**
     * What the session said before the wait. It reads as an answer and is stored as one
     * — that is the whole failure this guards.
     */
    private static final String STAND_IN = "The workflow is running; I'll report back when it lands.";

    private static final String RESULT_EVENT =
            "{\"type\":\"result\",\"result\":\"" + STAND_IN + "\","
                    + "\"total_cost_usd\":2.47,\"duration_ms\":139261}";

    /**
     * A stub {@code claude} that records the ceiling it was given and then prints the
     * lines a real one prints, in the real order: the plain-text warning first, the
     * stream-json result event last.
     */
    private void writeStub(Path dir, String... stdoutLines) throws Exception {
        writeStub(dir, List.of(), List.of(stdoutLines));
    }

    /**
     * Writes a stub CLI that prints {@code stderrLines} to stderr and {@code stdoutLines}
     * to stdout, in that order.
     *
     * <p>Which stream a line goes to is not a detail here. The real CLI prints the
     * abandoned-work warning on <b>stderr</b>, while its stream-json events come back on
     * stdout, and {@link ClaudeSessionRunner} reads exactly one stream. Detection
     * therefore works in production only because the runner sets
     * {@code redirectErrorStream(true)} — and a stub that printed the warning to stdout
     * would pass just the same with that redirect deleted, leaving the whole guard dead
     * against a green suite.
     */
    private void writeStub(Path dir, List<String> stderrLines, List<String> stdoutLines)
            throws Exception {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        script.append("printf '%s' \"$CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS\" > ")
                .append(dir.resolve("ceiling.txt")).append('\n');
        for (String line : stderrLines) {
            script.append("cat >&2 <<'CLAUDE_STUB_EOF'\n").append(line).append("\nCLAUDE_STUB_EOF\n");
        }
        for (String line : stdoutLines) {
            script.append("cat <<'CLAUDE_STUB_EOF'\n").append(line).append("\nCLAUDE_STUB_EOF\n");
        }
        Path stub = dir.resolve("claude-stub.sh");
        Files.writeString(stub, script.toString());
        assertThat(stub.toFile().setExecutable(true)).isTrue();
    }

    /** A runner pointed at the stub, with the CLI's own args left off. */
    private ClaudeSessionRunner runnerFor(Path dir, Long waitSeconds) {
        WorkerProperties props = new WorkerProperties();
        props.setClaudeCommand(dir.resolve("claude-stub.sh").toString());
        props.setExtraArgs(List.of());
        props.setWorkingDirectory(dir.toString());
        props.setBackgroundWaitSeconds(waitSeconds);
        return new ClaudeSessionRunner(props, new LiveLogRegistry(), new RunningSessionRegistry());
    }

    /** A runner on the project's own default wait. */
    private ClaudeSessionRunner runnerFor(Path dir) {
        return runnerFor(dir, new WorkerProperties().getBackgroundWaitSeconds());
    }

    /**
     * A runner whose wait is left unconfigured, on a given hard timeout — the shape the
     * live backend runs in, where {@code scripts/env.sh} passes
     * {@code --worker.timeout-seconds} on the command line and nothing passes a wait.
     */
    private ClaudeSessionRunner runnerOnTimeout(Path dir, long timeoutSeconds) {
        WorkerProperties props = new WorkerProperties();
        props.setClaudeCommand(dir.resolve("claude-stub.sh").toString());
        props.setExtraArgs(List.of());
        props.setWorkingDirectory(dir.toString());
        props.setTimeoutSeconds(timeoutSeconds);
        return new ClaudeSessionRunner(props, new LiveLogRegistry(), new RunningSessionRegistry());
    }

    /**
     * A wait that is deliberately not {@link WorkerProperties}' default.
     *
     * <p>It has to be. A child process inherits its parent's environment, and the parent
     * here is this project's own worker session, which sets
     * {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS} to exactly the default — so a stub
     * asked about the default reads back the right number whether the runner set it or
     * not. Asserting the default made this test pass with the runner's own
     * {@code environment().put(...)} deleted, every time the suite ran through
     * {@code scripts/deploy.sh}, which is every time it guards a release.
     */
    private static final long UNUSUAL_WAIT_SECONDS = 1234;

    @Test
    void aSessionIsAllowedToWaitForItsBackgroundWorkForAsLongAsTheConfigSays(@TempDir Path dir)
            throws Exception {
        // The CLI's own default is 600s. That is not a number this project chose, it is
        // three times shorter than the timeout it enforces, and it is applied silently,
        // so the ceiling has to be passed in rather than inherited.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir, UNUSUAL_WAIT_SECONDS).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the session should be given the configured wait, in milliseconds")
                .isEqualTo("1234000");
    }

    @Test
    void theWaitIsGivenToTheSessionRatherThanLeftToWhateverTheParentHappenedToExport(
            @TempDir Path dir) throws Exception {
        // The companion to the above, stated as its own rule because it is the one the
        // inherited environment can hide: whatever this project configures has to reach
        // the session, and it must not be possible to satisfy that by doing nothing.
        writeStub(dir, RESULT_EVENT);
        String inherited = System.getenv("CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS");

        runnerFor(dir, UNUSUAL_WAIT_SECONDS).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the configured wait must win over anything the parent exported (%s)", inherited)
                .isEqualTo("1234000")
                .isNotEqualTo(inherited);
    }

    @Test
    void aCeilingOfZeroIsPassedThroughAsWaitIndefinitely(@TempDir Path dir) throws Exception {
        // 0 is the CLI's own spelling of "no ceiling", so it has to survive the
        // seconds-to-milliseconds conversion rather than becoming a zero-length wait.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir, 0L).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt"))).isEqualTo("0");
    }

    @Test
    void aSessionThatRanOutOfPatienceIsNotReportedAsACleanFinish(@TempDir Path dir) throws Exception {
        // Everything about this run looks successful: exit 0, a terminal result event, a
        // cost, and an answer. The only thing that says otherwise is the warning line,
        // and it is gone from the output by the time the caller sees it.
        // The warning goes to stderr because that is where the real CLI puts it; the
        // result event to stdout. The runner only sees both because it merges the two.
        writeStub(dir, List.of(CEILING_HIT), List.of(RESULT_EVENT));

        ProcessResult result = runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(result.exitCode()).isZero();
        assertThat(result.abandonedBackgroundWork()).isTrue();
        // Why a flag is needed at all: the result event replaces the output wholesale,
        // so looking for the warning in finalOutput afterwards finds nothing.
        assertThat(result.finalOutput()).isEqualTo(STAND_IN).doesNotContain("Background tasks");
    }

    @Test
    void aSessionThatFinishedItsOwnWorkIsNotAccusedOfAbandoningAny(@TempDir Path dir) throws Exception {
        // The control. Identical run, one line short, and the flag has to be off — a
        // guard that fired on every session would send healthy work to Needs Attention.
        writeStub(dir, RESULT_EVENT);

        ProcessResult result = runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(result.abandonedBackgroundWork()).isFalse();
        assertThat(result.finalOutput()).isEqualTo(STAND_IN);
        assertThat(result.costUsd()).isEqualTo(2.47);
    }

    @Test
    void theWaitIsShorterThanTheHardTimeoutSoTheCliCanStillReportWhyItStopped() {
        // The two clocks start at different moments — the wait when the last turn ends,
        // the timeout when the process starts — so equal values would leave the CLI
        // being force-killed instead of winding up with a cost, a duration and a reason.
        //
        // Asserted over a range of timeouts rather than on the defaults alone: a version
        // of this that only read the defaults passed while the live backend, launched
        // with --worker.timeout-seconds=3600, killed background work at 1500s.
        for (long timeout : new long[]{600, 1800, 3600, 7200, 86_400}) {
            WorkerProperties props = new WorkerProperties();
            props.setTimeoutSeconds(timeout);

            assertThat(props.getBackgroundWaitSeconds())
                    .as("wait for a session allowed %ss", timeout)
                    .isPositive()
                    .isLessThan(timeout);
        }
    }

    @Test
    void raisingTheTimeoutRaisesHowLongBackgroundWorkIsWaitedFor(@TempDir Path dir)
            throws Exception {
        // The card's bug, at the number the live backend actually runs on. Before this,
        // a session launched with --worker.timeout-seconds=3600 was still handed the
        // 1500s written in application.yml — an hour to live, and the dynamic workflow
        // it started killed after 25 minutes with 35 minutes of its allowance unspent.
        writeStub(dir, RESULT_EVENT);

        runnerOnTimeout(dir, 3600).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("a session allowed 3600s should wait 3600s minus the wind-up headroom")
                .isEqualTo("3300000");
    }

    @Test
    void aWaitThatWasConfiguredOutrightStillWinsOverTheDerivedOne(@TempDir Path dir)
            throws Exception {
        // Deriving is the default, not the only option: an operator who writes a number
        // down means that number, whatever the timeout says.
        writeStub(dir, RESULT_EVENT);
        WorkerProperties props = new WorkerProperties();
        props.setClaudeCommand(dir.resolve("claude-stub.sh").toString());
        props.setExtraArgs(List.of());
        props.setWorkingDirectory(dir.toString());
        // A timeout that would derive 3300s, and a wait that says otherwise.
        props.setTimeoutSeconds(3600);
        props.setBackgroundWaitSeconds(UNUSUAL_WAIT_SECONDS);

        new ClaudeSessionRunner(props, new LiveLogRegistry(), new RunningSessionRegistry())
                .run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt"))).isEqualTo("1234000");
    }

    @Test
    void aTimeoutTooShortToSubtractHeadroomFromStillLeavesAWaitRatherThanNone() {
        // Guarding the arithmetic, not a configuration anyone runs: 0 is the CLI's
        // spelling of "wait indefinitely", so a derivation that reached it by
        // subtraction would turn the shortest timeout into the longest wait, and a
        // negative one would be nonsense passed to a child process.
        WorkerProperties props = new WorkerProperties();
        props.setTimeoutSeconds(30);

        assertThat(props.getBackgroundWaitSeconds()).isEqualTo(60);
    }

    @Test
    void nothingIsConfiguredByDefaultSoTheWaitIsTheOneDerivedFromTheTimeout() {
        // The distinction the fix rests on. If the shipped default were a number again,
        // it would be a number that stops following the timeout the moment one is passed
        // on the command line — which is exactly how the bug got in.
        WorkerProperties props = new WorkerProperties();

        assertThat(props.getConfiguredBackgroundWaitSeconds()).isNull();
        assertThat(props.getBackgroundWaitSeconds())
                .isEqualTo(props.getTimeoutSeconds() - WorkerProperties.BACKGROUND_WAIT_HEADROOM_SECONDS);
    }
}
