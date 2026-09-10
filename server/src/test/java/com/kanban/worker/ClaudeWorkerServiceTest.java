package com.kanban.worker;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the worker does with tasks a restart left behind.
 *
 * <p>The prompt and eligibility tests that used to live here moved to
 * {@code com.kanban.work} with the code they cover — the server builds prompts for every
 * worker now, not just this one. What is left is the recovery policy, which is still this
 * class's own: a session that died with the JVM gets one retry, and past the cap it goes to
 * Needs Attention rather than being run again over work it may already have done.
 *
 * <p>The collaborators a recovery pass never touches are passed as nulls.
 */
class ClaudeWorkerServiceTest {

    private Todo task(String title, boolean projectImprovement) {
        Todo t = new Todo();
        t.setTitle(title);
        t.setDescription("do the thing");
        t.setProjectImprovement(projectImprovement);
        return t;
    }

    @Test
    void aRestartLeavesAnOutsideWorkersClaimAlone() {
        TodoRepository repository = mock(TodoRepository.class);
        WorkerProperties props = mock(WorkerProperties.class);

        Todo claimed = task("Book the venue", false);
        claimed.setDelegated(true);
        claimed.setDelegatedTo("outside-bot");
        claimed.setStatus(TodoStatus.IN_PROGRESS);
        Todo ourOwnSession = task("Fix the board layout", true);
        ourOwnSession.setStatus(TodoStatus.IN_PROGRESS);

        when(repository.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS))
                .thenReturn(List.of(claimed, ourOwnSession));
        when(repository.findByClassifyingTrue()).thenReturn(List.of());
        when(props.getMaxAttempts()).thenReturn(3);

        new ClaudeWorkerService(repository, props, null, null, null, null, null).requeueOrphans();

        // Our own killed session is re-queued...
        assertThat(ourOwnSession.getStatus()).isEqualTo(TodoStatus.PENDING);
        verify(repository).save(ourOwnSession);
        // ...but the delegated one is IN_PROGRESS because somebody OUTSIDE took it.
        // No session of ours died with the restart, and that worker is probably
        // still on the job — yanking it back would drop their claim.
        assertThat(claimed.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(claimed.getDelegatedTo()).isEqualTo("outside-bot");
        verify(repository, never()).save(claimed);
    }

    // --- orphan re-queue cap (why an already-run task must not be re-executed forever) ---

    private Todo inProgress(int attempts) {
        Todo t = task("Build the 潜规则 doc section", false);
        t.setStatus(TodoStatus.IN_PROGRESS);
        t.setAttempts(attempts);
        return t;
    }

    @Test
    void orphanBelowAttemptCapIsRequeuedForOneRetry() {
        TodoRepository repo = mock(TodoRepository.class);
        WorkerProperties props = new WorkerProperties(); // maxAttempts default 2
        Todo t = inProgress(1); // ran once, killed by a restart
        when(repo.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS)).thenReturn(List.of(t));
        when(repo.findByClassifyingTrue()).thenReturn(List.of());

        new ClaudeWorkerService(repo, props, null, null, null, null, null).requeueOrphans();

        // One automatic recovery retry is still allowed after a genuine crash.
        assertThat(t.getStatus()).isEqualTo(TodoStatus.PENDING);
    }

    @Test
    void orphanAtAttemptCapGoesToNeedsAttentionInsteadOfRerunning() {
        TodoRepository repo = mock(TodoRepository.class);
        WorkerProperties props = new WorkerProperties(); // maxAttempts default 2
        Todo t = inProgress(2); // already started twice — re-running would duplicate work
        when(repo.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS)).thenReturn(List.of(t));
        when(repo.findByClassifyingTrue()).thenReturn(List.of());

        new ClaudeWorkerService(repo, props, null, null, null, null, null).requeueOrphans();

        assertThat(t.getStatus()).isEqualTo(TodoStatus.FAILED);
        assertThat(t.getResult()).contains("Interrupted by a backend restart");
        assertThat(t.getResult()).contains("duplicate");
    }

    @Test
    void maxAttemptsOfOneNeverAutoRetries() {
        TodoRepository repo = mock(TodoRepository.class);
        WorkerProperties props = new WorkerProperties();
        props.setMaxAttempts(1); // opt out of any automatic retry
        Todo t = inProgress(1);
        when(repo.findByStatusOrderByCreatedAtAsc(TodoStatus.IN_PROGRESS)).thenReturn(List.of(t));
        when(repo.findByClassifyingTrue()).thenReturn(List.of());

        new ClaudeWorkerService(repo, props, null, null, null, null, null).requeueOrphans();

        assertThat(t.getStatus()).isEqualTo(TodoStatus.FAILED);
    }
}
