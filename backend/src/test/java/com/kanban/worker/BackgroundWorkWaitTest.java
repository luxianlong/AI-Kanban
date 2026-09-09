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
    private void writeStub(Path dir, String... lines) throws Exception {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        script.append("printf '%s' \"$CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS\" > ")
                .append(dir.resolve("ceiling.txt")).append('\n');
        for (String line : lines) {
            script.append("cat <<'CLAUDE_STUB_EOF'\n").append(line).append("\nCLAUDE_STUB_EOF\n");
        }
        Path stub = dir.resolve("claude-stub.sh");
        Files.writeString(stub, script.toString());
        assertThat(stub.toFile().setExecutable(true)).isTrue();
    }

    /** A runner pointed at the stub, with the CLI's own args left off. */
    private ClaudeSessionRunner runnerFor(Path dir, long waitSeconds) {
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

    @Test
    void aSessionIsAllowedToWaitForItsBackgroundWorkForAsLongAsTheConfigSays(@TempDir Path dir)
            throws Exception {
        // The CLI's own default is 600s. That is not a number this project chose, it is
        // three times shorter than the timeout it enforces, and it is applied silently,
        // so the ceiling has to be passed in rather than inherited.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the session should be given the configured wait, in milliseconds")
                .isEqualTo("1500000");
    }

    @Test
    void aCeilingOfZeroIsPassedThroughAsWaitIndefinitely(@TempDir Path dir) throws Exception {
        // 0 is the CLI's own spelling of "no ceiling", so it has to survive the
        // seconds-to-milliseconds conversion rather than becoming a zero-length wait.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir, 0).run("todo:1", "go", dir.toFile(), List.of());

        assertThat(Files.readString(dir.resolve("ceiling.txt"))).isEqualTo("0");
    }

    @Test
    void aSessionThatRanOutOfPatienceIsNotReportedAsACleanFinish(@TempDir Path dir) throws Exception {
        // Everything about this run looks successful: exit 0, a terminal result event, a
        // cost, and an answer. The only thing that says otherwise is the warning line,
        // and it is gone from the output by the time the caller sees it.
        writeStub(dir, CEILING_HIT, RESULT_EVENT);

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
        WorkerProperties props = new WorkerProperties();

        assertThat(props.getBackgroundWaitSeconds()).isLessThan(props.getTimeoutSeconds());
    }
}
