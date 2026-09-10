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
 * <p>This is a different question from the one {@link BackgroundWorkWaitTest} asks, and
 * the difference is the whole bug. The wait was correct in code and correct on the
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
}
