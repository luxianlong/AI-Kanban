package com.kanban.worker;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the prompt the worker hands to the Claude CLI. A plain
 * (non-story) task never touches the repository/props/liveLog collaborators in
 * {@link ClaudeWorkerService#buildPrompt}, so we can construct the service with
 * nulls and assert on the generated prompt directly.
 */
class ClaudeWorkerServiceTest {

    private final ClaudeWorkerService service = new ClaudeWorkerService(null, null, null);

    private Todo task(String title, boolean projectImprovement) {
        Todo t = new Todo();
        t.setTitle(title);
        t.setDescription("do the thing");
        t.setProjectImprovement(projectImprovement);
        return t;
    }

    @Test
    void generalTaskIsToldNotToRestartOrDeploy() {
        String prompt = service.buildPrompt(task("Research gold prices", false));

        assertThat(prompt).contains("GENERAL task");
        assertThat(prompt).contains("do NOT run scripts/deploy.sh");
        assertThat(prompt).contains("do NOT ");
        assertThat(prompt).contains("restart the website");
        // A general task must not be pointed at the code-change / deploy flow.
        assertThat(prompt).doesNotContain("how to make changes");
    }

    @Test
    void generalTaskMayChangeResourcesButNotKanbanCode() {
        String prompt = service.buildPrompt(task("Write a travel guide page", false));

        // It is explicitly allowed to produce/change its resource/output files...
        assertThat(prompt).contains("RESOURCE");
        assertThat(prompt).contains("You MAY create or change");
        // ...but not the Kanban application's own code.
        assertThat(prompt).contains("OWN CODE");
    }

    @Test
    void projectImprovementTaskKeepsTheDeployGuidance() {
        String prompt = service.buildPrompt(task("Fix the board layout", true));

        assertThat(prompt).contains("CLAUDE.md");
        assertThat(prompt).contains("how to make changes");
        // The improvement task must NOT carry the general-task restriction.
        assertThat(prompt).doesNotContain("do NOT run scripts/deploy.sh");
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

        new ClaudeWorkerService(repo, props, null).requeueOrphans();

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

        new ClaudeWorkerService(repo, props, null).requeueOrphans();

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

        new ClaudeWorkerService(repo, props, null).requeueOrphans();

        assertThat(t.getStatus()).isEqualTo(TodoStatus.FAILED);
    }
}
