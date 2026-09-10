package com.kanban.workflow;

import com.kanban.worker.SessionLimits;
import com.kanban.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an ultracode step is actually given by the <em>shipped configuration</em>, rather
 * than by a hand-built {@link WorkflowRunnerService}.
 *
 * <p>This asks a different question from {@link WorkflowRunnerServiceTest}, and the
 * difference is the one that has bitten this setting's neighbour twice. The arithmetic
 * can be right in code and right on the defaults while being wrong in production,
 * because {@code application.yml} holds one number and {@code scripts/env.sh} launches
 * the backend with {@code --worker.timeout-seconds=3600}, which overrides it. So this
 * boots the real yml and overrides only the timeout — exactly what that command line
 * does — and checks the numbers that come out the far end.
 *
 * <p>If the multiplier is ever replaced by an absolute number of seconds, this fails.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ultracodetimeoutdb;DB_CLOSE_DELAY=-1",
        // The live shape: what scripts/env.sh passes on the command line.
        "worker.timeout-seconds=3600",
})
class UltracodeTimeoutConfigTest {

    @Autowired
    private WorkflowRunnerService service;

    @Autowired
    private WorkerProperties props;

    @Test
    void theShippedConfigGivesAnUltracodeStepAMultipleOfTheTimeoutThatIsActuallyInForce() {
        SessionLimits ordinary = service.limitsFor(EffortLevel.MAX);
        SessionLimits ultracode = service.limitsFor(EffortLevel.ULTRACODE);

        assertThat(props.getTimeoutSeconds()).isEqualTo(3600);
        assertThat(ordinary.timeoutSeconds())
                .as("every level but ultracode runs on the worker's own clocks")
                .isEqualTo(3600);
        assertThat(ultracode.timeoutSeconds())
                .as("the yml ships a multiplier of 3, applied to the 3600 in force"
                        + " rather than to the 1800 written in the file")
                .isEqualTo(10_800);
    }

    @Test
    void theShippedConfigLetsThatStepWaitForItsSubSessionsForAsLongAsItMayLive() {
        // The failure this half prevents is silent: the step stays alive, its background
        // work is killed under it, and what gets stored and passed downstream is the
        // sentence it wrote before the kill.
        SessionLimits ultracode = service.limitsFor(EffortLevel.ULTRACODE);

        assertThat(props.getConfiguredBackgroundWaitSeconds())
                .as("application.yml must not pin the wait — a pinned number stops"
                        + " following the timeout of the session it belongs to")
                .isNull();
        assertThat(ultracode.backgroundWaitSeconds())
                .isEqualTo(10_500)
                .isGreaterThan(props.getBackgroundWaitSeconds())
                .isLessThan(ultracode.timeoutSeconds());
    }
}
