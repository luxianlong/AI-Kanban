package com.kanban.work;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which pending tasks a worker may take. Moved here with {@link TaskEligibility} so the rule
 * is tested where both the in-process worker and the agent API read it from.
 */
class TaskEligibilityTest {

    private final TaskEligibility eligibility = new TaskEligibility(mock(TodoRepository.class));

    private Todo task(String title) {
        Todo t = new Todo();
        t.setTitle(title);
        t.setDescription("do the thing");
        return t;
    }

    @Test
    void aDelegatedTaskIsNeverPickedUpByTheWorker() {
        Todo ours = task("Fix the board layout");
        Todo handedOut = task("Book the venue");
        handedOut.setDelegated(true);

        // The whole point: this server leaves it in the queue for an outside
        // worker to take through the public API, however long that takes.
        assertThat(eligibility.isEligible(handedOut)).isFalse();
        assertThat(eligibility.isEligible(ours)).isTrue();
    }
}
