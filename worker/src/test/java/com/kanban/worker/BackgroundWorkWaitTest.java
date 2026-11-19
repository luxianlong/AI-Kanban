package com.kanban.worker;

import com.kanban.protocol.SessionLimitsDto;
import com.kanban.worker.session.ClaudeSessionRunner;
import com.kanban.worker.session.ClaudeSessionRunner.ProcessResult;
import com.kanban.worker.session.RunningSessions;
import com.kanban.worker.session.SessionSettings;
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

    /** The structured warning event a real Copilot CLI emits when it stops waiting and kills the work. */
    private static final String CEILING_HIT =
            "{\"type\":\"session.warning\",\"data\":{\"warningType\":\"background_task_wait_timeout\","
                    + "\"message\":\"Timed out after 1500s waiting for background tasks to finish;"
                    + " giving up on the wait with work still pending. Set"
                    + " COPILOT_TASK_WAIT_TIMEOUT_SECONDS to change the limit.\"}}";

    /**
     * What the session said before the wait. It reads as an answer and is stored as one
     * — that is the whole failure this guards.
     */
    private static final String STAND_IN = "The workflow is running; I'll report back when it lands.";

    private static final String ASSISTANT_MESSAGE_EVENT =
            "{\"type\":\"assistant.message\",\"data\":{\"messageId\":\"m1\",\"model\":\"gpt\","
                    + "\"content\":\"" + STAND_IN + "\",\"toolRequests\":[],\"turnId\":\"t1\"}}";

    private static final String RESULT_EVENT =
            "{\"type\":\"result\",\"sessionId\":\"s1\",\"exitCode\":0,"
                    + "\"usage\":{\"premiumRequests\":1,\"sessionDurationMs\":139261}}";

    /**
     * A stub {@code copilot} that records the ceiling it was given and then prints the
     * lines a real one prints, in the real order: the assistant's stand-in reply, then
     * (if the wait ran out) the structured warning event, then the terminal result event.
     */
    private void writeStub(Path dir, String... stdoutLines) throws Exception {
        writeStub(dir, List.of(), List.of(stdoutLines));
    }

    /**
     * Writes a stub CLI that prints {@code stderrLines} to stderr and {@code stdoutLines}
     * to stdout, in that order.
     *
     * <p>Which stream a line goes to is not a detail here. A real Copilot CLI's events all
     * arrive on stdout (unlike Claude Code's stderr warning), so every event here is
     * emitted on stdout; {@link ClaudeSessionRunner} still sets
     * {@code redirectErrorStream(true)}, so this is also exercised whenever stderrLines
     * is non-empty.
     */
    private void writeStub(Path dir, List<String> stderrLines, List<String> stdoutLines)
            throws Exception {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        script.append("printf '%s' \"$COPILOT_TASK_WAIT_TIMEOUT_SECONDS\" > ")
                .append(dir.resolve("ceiling.txt")).append('\n');
        for (String line : stderrLines) {
            script.append("cat >&2 <<'COPILOT_STUB_EOF'\n").append(line).append("\nCOPILOT_STUB_EOF\n");
        }
        for (String line : stdoutLines) {
            script.append("cat <<'COPILOT_STUB_EOF'\n").append(line).append("\nCOPILOT_STUB_EOF\n");
        }
        Path stub = dir.resolve("copilot-stub.sh");
        Files.writeString(stub, script.toString());
        assertThat(stub.toFile().setExecutable(true)).isTrue();
    }

    /** A runner pointed at the stub, with the CLI's own args left off. */
    private ClaudeSessionRunner runnerFor(Path dir) {
        return new ClaudeSessionRunner(
                new StubSettings(dir.resolve("copilot-stub.sh").toString()),
                (key, line) -> { },
                new RunningSessions());
    }

    /**
     * The clocks a unit arrives with.
     *
     * <p>On the server these two are derived together from whatever timeout is in force; here
     * they are simply what the work unit said. That is the whole reason both travel together —
     * a worker handed only one of them would be back in the failure this class guards, running
     * a session for an hour while killing its background work after twenty-five minutes.
     */
    private SessionLimitsDto limits(long waitSeconds) {
        return new SessionLimitsDto(600, waitSeconds);
    }

    /** The settings a worker is configured with, pointed at the stub. */
    private record StubSettings(String command) implements SessionSettings {
        @Override
        public String claudeCommand() {
            return command;
        }

        @Override
        public List<String> extraArgs() {
            return List.of();
        }
    }

    /**
     * A wait that is deliberately not {@link WorkerProperties}' default.
     *
     * <p>It has to be. A child process inherits its parent's environment, and the parent
     * here is this project's own worker session, which sets
     * {@code COPILOT_TASK_WAIT_TIMEOUT_SECONDS} to exactly the default — so a stub
     * asked about the default reads back the right number whether the runner set it or
     * not. Asserting the default made this test pass with the runner's own
     * {@code environment().put(...)} deleted, every time the suite ran through
     * {@code scripts/deploy.sh}, which is every time it guards a release.
     */
    private static final long UNUSUAL_WAIT_SECONDS = 1234;

    @Test
    void aSessionIsAllowedToWaitForItsBackgroundWorkForAsLongAsTheConfigSays(@TempDir Path dir)
            throws Exception {
        // The CLI's own default is 600s. That is not a number this project chose, and it
        // is applied silently, so the ceiling has to be passed in rather than inherited.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of(), limits(UNUSUAL_WAIT_SECONDS));

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the session should be given the configured wait, in seconds")
                .isEqualTo("1234");
    }

    @Test
    void theWaitIsGivenToTheSessionRatherThanLeftToWhateverTheParentHappenedToExport(
            @TempDir Path dir) throws Exception {
        // The companion to the above, stated as its own rule because it is the one the
        // inherited environment can hide: whatever this project configures has to reach
        // the session, and it must not be possible to satisfy that by doing nothing.
        writeStub(dir, RESULT_EVENT);
        String inherited = System.getenv("COPILOT_TASK_WAIT_TIMEOUT_SECONDS");

        runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of(), limits(UNUSUAL_WAIT_SECONDS));

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the configured wait must win over anything the parent exported (%s)", inherited)
                .isEqualTo("1234")
                .isNotEqualTo(inherited);
    }

    @Test
    void aWaitOfZeroIsApproximatedByTheSessionsOwnHardTimeout(@TempDir Path dir) throws Exception {
        // Unlike Claude Code's old CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0 ("wait
        // indefinitely"), Copilot CLI's own COPILOT_TASK_WAIT_TIMEOUT_SECONDS=0 means
        // "don't wait at all" — the opposite sentinel. A configured wait of 0 (the old
        // "indefinite" signal) is approximated instead by the session's own hard timeout,
        // since the destroyForcibly() below fires at that same ceiling regardless.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of(), limits(0L));

        assertThat(Files.readString(dir.resolve("ceiling.txt"))).isEqualTo("600");
    }

    @Test
    void aSessionThatRanOutOfPatienceIsNotReportedAsACleanFinish(@TempDir Path dir) throws Exception {
        // Everything about this run looks successful: exit 0, a terminal result event,
        // and an answer. The only thing that says otherwise is the structured warning
        // event, and it carries no answer text of its own to show up in finalOutput.
        writeStub(dir, ASSISTANT_MESSAGE_EVENT, CEILING_HIT, RESULT_EVENT);

        ProcessResult result = runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of(), limits(1500));

        assertThat(result.exitCode()).isZero();
        assertThat(result.abandonedBackgroundWork()).isTrue();
        // Why a flag is needed at all: the warning event carries no answer, so looking
        // for it in finalOutput afterwards finds nothing — finalOutput is still exactly
        // the stand-in the session wrote before the wait ran out.
        assertThat(result.finalOutput()).isEqualTo(STAND_IN).doesNotContain("background_task_wait_timeout");
    }

    @Test
    void aSessionThatFinishedItsOwnWorkIsNotAccusedOfAbandoningAny(@TempDir Path dir) throws Exception {
        // The control. Identical run, one event short, and the flag has to be off — a
        // guard that fired on every session would send healthy work to Needs Attention.
        writeStub(dir, ASSISTANT_MESSAGE_EVENT, RESULT_EVENT);

        ProcessResult result = runnerFor(dir).run("todo:1", "go", dir.toFile(), List.of(), limits(1500));

        assertThat(result.abandonedBackgroundWork()).isFalse();
        assertThat(result.finalOutput()).isEqualTo(STAND_IN);
        // Copilot CLI reports usage in premium requests, not a dollar figure — there is
        // no equivalent of Claude Code's total_cost_usd, so costUsd always stays null.
        assertThat(result.costUsd()).isNull();
    }

    @Test
    void aSessionAllowedToLiveLongerThanTheWorkerWaitsLongerToo(@TempDir Path dir)
            throws Exception {
        // A workflow step at the ultracode effort level is given a multiple of the
        // worker's timeout, because its real work happens in sub-sessions it starts and
        // waits for. Giving it only the longer life would be worse than giving it
        // nothing: it would sit for three hours with its sub-sessions killed at
        // fifty-five minutes, and report the promise it wrote before the kill.
        writeStub(dir, RESULT_EVENT);

        runnerFor(dir).run("node:1", "go", dir.toFile(), List.of(),
                new SessionLimitsDto(10_800, 10_500));

        assertThat(Files.readString(dir.resolve("ceiling.txt")))
                .as("the ceiling should follow the ceiling this session was given")
                .isEqualTo("10500");
    }

    @Test
    void theHardTimeoutAlsoComesFromTheLimitsTheSessionWasGiven(@TempDir Path dir)
            throws Exception {
        // The other clock, and the one the ultracode change is actually about. The
        // properties here allow 1800s, so a runner still reading them would sit through
        // the stub's sleep and come back clean — this only goes red-to-green on the
        // limits being what waitFor() is handed.
        Path stub = dir.resolve("copilot-stub.sh");
        Files.writeString(stub, "#!/bin/sh\nsleep 30\n");
        assertThat(stub.toFile().setExecutable(true)).isTrue();

        long startedAt = System.currentTimeMillis();
        ProcessResult result = runnerFor(dir).run("node:1", "go", dir.toFile(), List.of(),
                new SessionLimitsDto(1, 1));

        assertThat(result.timedOut()).isTrue();
        assertThat(System.currentTimeMillis() - startedAt)
                .as("killed on the second it was given")
                .isLessThan(20_000);
    }
}
