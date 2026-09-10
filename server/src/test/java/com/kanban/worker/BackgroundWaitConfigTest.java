package com.kanban.worker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The background wait as the <em>shipped configuration</em> produces it, rather than as
 * a bare {@code new WorkerProperties()} produces it.
 *
 * <p>This is a different question from the one the worker module's {@code BackgroundWorkWaitTest}
 * asks — that one checks the ceiling reaches the process; this one checks the number is right —
 * and the difference is the whole bug. The wait was correct in code and correct on the
 * defaults; it was wrong in production because {@code application.yml} pinned it to 1500
 * while {@code scripts/env.sh} launched the backend with
 * {@code --worker.timeout-seconds=3600}. A session got an hour to live and the dynamic
 * workflow it started was killed after 25 minutes — and no test could see it, because no
 * test read the yml.
 *
 * <p>So this one boots the real configuration and overrides only the timeout, which is
 * exactly what a command-line argument does to it. If a number is ever written back into
 * the yml, this fails.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:bgwaitconfigdb;DB_CLOSE_DELAY=-1",
        // What scripts/env.sh passes on the command line. Test properties and command
        // line arguments both outrank application.yml, so this is the live shape.
        "worker.timeout-seconds=3600",
})
class BackgroundWaitConfigTest {

    @Autowired
    private WorkerProperties props;

    @Test
    void theShippedConfigLetsAStepWaitForItsBackgroundWorkForAsLongAsTheStepMayLive() {
        assertThat(props.getTimeoutSeconds()).isEqualTo(3600);
        assertThat(props.getConfiguredBackgroundWaitSeconds())
                .as("application.yml must not pin the wait — a pinned number stops"
                        + " following the timeout the moment one is passed on the command line")
                .isNull();
        assertThat(props.getBackgroundWaitSeconds()).isEqualTo(3300);
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
            WorkerProperties derived = new WorkerProperties();
            derived.setTimeoutSeconds(timeout);

            assertThat(derived.getBackgroundWaitSeconds())
                    .as("wait for a session allowed %ss", timeout)
                    .isPositive()
                    .isLessThan(timeout);
        }
    }

    @Test
    void aWaitThatWasConfiguredOutrightStillWinsOverTheDerivedOne() {
        // Deriving is the default, not the only option: an operator who writes a number
        // down means that number, whatever the timeout says.
        WorkerProperties pinned = new WorkerProperties();
        pinned.setTimeoutSeconds(3600);   // would derive 3300
        pinned.setBackgroundWaitSeconds(1234L);

        assertThat(pinned.getBackgroundWaitSeconds()).isEqualTo(1234);
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
