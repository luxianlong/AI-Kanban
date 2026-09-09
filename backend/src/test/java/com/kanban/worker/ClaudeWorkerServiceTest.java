package com.kanban.worker;

import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the prompt the worker hands to the Claude CLI. A plain
 * (non-story) task never touches the repository/props/liveLog collaborators in
 * {@link ClaudeWorkerService#buildPrompt}, so we can construct the service with
 * nulls (plus a stubbed attachment store) and assert on the generated prompt
 * directly.
 */
class ClaudeWorkerServiceTest {

    private final AttachmentService attachments = mock(AttachmentService.class);
    private final ClaudeWorkerService service =
            new ClaudeWorkerService(null, null, null, null, null, attachments);

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

    // --- delegated (代理) tasks: handed out, never run here (task #471) ---

    @Test
    void aDelegatedTaskIsNeverPickedUpByTheWorker() {
        Todo ours = task("Fix the board layout", true);
        Todo handedOut = task("Book the venue", false);
        handedOut.setDelegated(true);

        // The whole point: this server leaves it in the queue for an outside
        // worker to take through the public API, however long that takes.
        assertThat(service.isEligible(handedOut)).isFalse();
        assertThat(service.isEligible(ours)).isTrue();
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

        new ClaudeWorkerService(repository, props, null, null, null, attachments).requeueOrphans();

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

    // --- attached images (the agent must be told where the real files are) ---

    private Attachment image(String id, String fileName) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setFileName(fileName);
        a.setContentType("image/png");
        a.setStoredName(id + ".png");
        return a;
    }

    @Test
    void attachedImagesArePassedToTheAgentAsReadablePaths() {
        Todo t = task("Rebuild this screen", false);
        ReflectionTestUtils.setField(t, "id", 42L);
        Attachment a = image("abc", "screenshot.png");
        when(attachments.listFor(42L)).thenReturn(List.of(a));
        when(attachments.pathOf(a)).thenReturn(Path.of("/srv/data/attachments/abc.png"));

        String prompt = service.buildPrompt(t);

        assertThat(prompt).contains("ATTACHED IMAGES (1)");
        // The absolute server-side path is the whole point: the session opens it.
        assertThat(prompt).contains("/srv/data/attachments/abc.png");
        assertThat(prompt).contains("screenshot.png");
        assertThat(prompt).contains("Read tool");
    }

    @Test
    void taskWithoutImagesGetsNoAttachmentSection() {
        Todo t = task("Write a haiku", false);
        ReflectionTestUtils.setField(t, "id", 7L);
        when(attachments.listFor(7L)).thenReturn(List.of());

        assertThat(service.buildPrompt(t)).doesNotContain("ATTACHED IMAGES");
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

        new ClaudeWorkerService(repo, props, null, null, null, null).requeueOrphans();

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

        new ClaudeWorkerService(repo, props, null, null, null, null).requeueOrphans();

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

        new ClaudeWorkerService(repo, props, null, null, null, null).requeueOrphans();

        assertThat(t.getStatus()).isEqualTo(TodoStatus.FAILED);
    }
}
