package com.kanban.work;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.springframework.stereotype.Component;

/**
 * Whether a pending task may be picked up right now.
 *
 * <p>Extracted from {@code ClaudeWorkerService} so the in-process worker and the agent API
 * that hands work to remote workers cannot drift apart on it. Two answers to "may this run?"
 * is one answer too many: a rule that lived in only one of them would let a remote worker
 * start a story's third step while its second was still going.
 */
@Component
public class TaskEligibility {

    private final TodoRepository repository;

    public TaskEligibility(TodoRepository repository) {
        this.repository = repository;
    }

    /**
     * Whether a worker may pick a pending task up on this tick.
     *
     * <ul>
     *   <li>A <em>delegated</em> (代理) task is never ours to run at all: it waits
     *       in the queue until an outside worker finds it through the public API
     *       ({@code /api/public/tasks}) and reports the outcome back.
     *   <li>A task still held by the async story classifier waits until that pass
     *       clears the hold, so it can't run before it has been filed.
     *   <li>Story steps run in order: a step waits until every earlier step in its
     *       chain has RESOLVED, so a story never runs two tasks at once and halts
     *       if one of its steps fails.
     * </ul>
     */
    public boolean isEligible(Todo todo) {
        return !todo.isDelegated() && !todo.isClassifying() && !isBlockedByStory(todo);
    }

    /**
     * A story step is blocked while any earlier step in the same chain is not yet
     * RESOLVED (still pending, running, or failed). Standalone todos are never
     * blocked. Keeping blocked steps in PENDING means they flow automatically the
     * moment their predecessor finishes — no extra bookkeeping needed.
     */
    private boolean isBlockedByStory(Todo todo) {
        String storyId = todo.getStoryId();
        if (storyId == null || todo.getStoryOrder() == null) {
            return false;
        }
        int order = todo.getStoryOrder();
        for (Todo step : repository.findByStoryIdOrderByStoryOrderAsc(storyId)) {
            if (step.getStoryOrder() != null
                    && step.getStoryOrder() < order
                    && step.getStatus() != TodoStatus.RESOLVED) {
                return true;
            }
        }
        return false;
    }
}
