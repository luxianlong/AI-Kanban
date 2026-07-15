package com.kanban.worker;

import com.kanban.todo.Todo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void projectImprovementTaskKeepsTheDeployGuidance() {
        String prompt = service.buildPrompt(task("Fix the board layout", true));

        assertThat(prompt).contains("CLAUDE.md");
        assertThat(prompt).contains("how to make changes");
        // The improvement task must NOT carry the general-task restriction.
        assertThat(prompt).doesNotContain("do NOT run scripts/deploy.sh");
    }
}
