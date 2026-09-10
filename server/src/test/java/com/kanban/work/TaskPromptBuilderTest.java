package com.kanban.work;

import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.protocol.AttachmentsPlaceholder;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the prompt handed to the Claude CLI for a board task.
 *
 * <p>These moved here with {@link TaskPromptBuilder} itself when the prompt stopped being the
 * in-process worker's business: the server builds it for every worker, local or remote, so
 * this is where the wording is pinned down. A plain (non-story) task never reads the
 * repository, so it is passed as a mock that is simply never used.
 */
class TaskPromptBuilderTest {

    private final AttachmentService attachments = mock(AttachmentService.class);
    private final TaskPromptBuilder prompts =
            new TaskPromptBuilder(mock(TodoRepository.class), attachments);

    private Todo task(String title, boolean projectImprovement) {
        Todo t = new Todo();
        t.setTitle(title);
        t.setDescription("do the thing");
        t.setProjectImprovement(projectImprovement);
        return t;
    }

    @Test
    void generalTaskIsToldNotToRestartOrDeploy() {
        String prompt = prompts.buildPrompt(task("Research gold prices", false));

        assertThat(prompt).contains("GENERAL task");
        assertThat(prompt).contains("do NOT run scripts/deploy.sh");
        assertThat(prompt).contains("do NOT ");
        assertThat(prompt).contains("restart the website");
        // A general task must not be pointed at the code-change / deploy flow.
        assertThat(prompt).doesNotContain("how to make changes");
    }

    @Test
    void generalTaskMayChangeResourcesButNotKanbanCode() {
        String prompt = prompts.buildPrompt(task("Write a travel guide page", false));

        // It is explicitly allowed to produce/change its resource/output files...
        assertThat(prompt).contains("RESOURCE");
        assertThat(prompt).contains("You MAY create or change");
        // ...but not the Kanban application's own code.
        assertThat(prompt).contains("OWN CODE");
    }

    @Test
    void projectImprovementTaskKeepsTheDeployGuidance() {
        String prompt = prompts.buildPrompt(task("Fix the board layout", true));

        assertThat(prompt).contains("CLAUDE.md");
        assertThat(prompt).contains("how to make changes");
        // The improvement task must NOT carry the general-task restriction.
        assertThat(prompt).doesNotContain("do NOT run scripts/deploy.sh");
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
    void attachedImagesAreAnnouncedButTheirPathsAreLeftToTheWorker() {
        Todo t = task("Rebuild this screen", false);
        ReflectionTestUtils.setField(t, "id", 42L);
        Attachment a = image("abc", "screenshot.png");
        when(attachments.listFor(42L)).thenReturn(List.of(a));
        when(attachments.pathOf(a)).thenReturn(Path.of("/srv/data/attachments/abc.png"));

        String prompt = prompts.buildPrompt(t);

        assertThat(prompt).contains("ATTACHED IMAGES (1)");
        assertThat(prompt).contains("Read tool");
        // The paths are the worker's to fill in. These files live beside THIS server's
        // database, so an absolute path from here would name nothing on the machine the
        // session actually runs on — and a session told to open it would simply fail.
        assertThat(prompt).contains(AttachmentsPlaceholder.TOKEN);
        assertThat(prompt)
                .as("a server-side path must never reach the prompt")
                .doesNotContain("/srv/data/attachments/abc.png");
    }

    @Test
    void taskWithoutImagesGetsNoAttachmentSection() {
        Todo t = task("Write a haiku", false);
        ReflectionTestUtils.setField(t, "id", 7L);
        when(attachments.listFor(7L)).thenReturn(List.of());

        assertThat(prompts.buildPrompt(t)).doesNotContain("ATTACHED IMAGES");
    }
}
