package com.kanban.worker;

import com.kanban.attachment.AttachmentService;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Shutdown must not be blocked by a running Claude session.
 *
 * <p>A worker tick sits in {@link Process#waitFor} for up to
 * {@code worker.timeout-seconds} (30 minutes). While it did, the JVM could not honour
 * SIGTERM, so every restart fell through to SIGKILL — and a SIGKILL landing inside
 * H2's shutdown compaction truncates kanban.mv.db past any hope of recovery (that is
 * how the live database was lost on 2026-08-29). These tests pin the two behaviours
 * that keep restarts on the clean-shutdown path.
 */
class ShutdownAbortTest {

    /** A real, long-running child process, so we test actual termination not a mock. */
    private Process sleeper() throws Exception {
        return new ProcessBuilder("sleep", "300").start();
    }

    @Test
    void abortAllTerminatesEveryRunningSession() throws Exception {
        RunningSessionRegistry sessions = new RunningSessionRegistry();
        Process a = sleeper();
        Process b = sleeper();
        sessions.register(1L, a);
        sessions.register(2L, b);

        assertThat(sessions.abortAll()).isEqualTo(2);

        // Both must actually die, and promptly — that is the whole point.
        assertThat(a.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(b.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void abortAllDoesNotMarkSessionsAsUserStopped() throws Exception {
        RunningSessionRegistry sessions = new RunningSessionRegistry();
        Process p = sleeper();
        sessions.register(7L, p);

        sessions.abortAll();
        p.waitFor(10, TimeUnit.SECONDS);

        // A shutdown is not a user pressing "stop": if this flag leaked, the worker
        // would record the run as deliberately stopped (a terminal FAILED state)
        // instead of leaving it IN_PROGRESS for requeueOrphans() to pick back up.
        assertThat(sessions.consumeStopRequested(7L)).isFalse();
    }

    @Test
    void abortAllOnAnIdleRegistryIsANoOp() {
        assertThat(new RunningSessionRegistry().abortAll()).isZero();
    }

    @Test
    void pollStopsPickingUpWorkOnceShutdownHasStarted() {
        TodoRepository repository = mock(TodoRepository.class);
        WorkerProperties props = new WorkerProperties();
        props.setEnabled(true);
        RunningSessionRegistry sessions = new RunningSessionRegistry();
        ClaudeWorkerService service = new ClaudeWorkerService(
                repository, props, mock(LiveLogRegistry.class), sessions,
                mock(ClaudeSessionRunner.class), mock(AttachmentService.class));

        service.shutdown();
        service.poll();

        // Not even a read: claiming a todo here would strand it IN_PROGRESS with an
        // attempt burned, for a run that can never start.
        verify(repository, never()).findByStatusOrderByCreatedAtAsc(TodoStatus.PENDING);
    }
}
