package com.kanban.work;

import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the prompt for one board task.
 *
 * <p>Extracted from {@code ClaudeWorkerService} because a remote worker cannot build this
 * itself: the story context below is assembled from other rows in the database, and a worker
 * has no database. The server therefore resolves everything and sends finished text, which is
 * also what keeps a worker from needing a model of the board.
 */
@Component
public class TaskPromptBuilder {

    /** Agents print this line to mark a task as failed even when the CLI exits 0. */
    public static final String FAIL_SENTINEL = "[[TASK_FAILED]]";

    /** Base URL the worker's agent uses to call back into this API. */
    private static final String API_BASE = "http://localhost:8080";

    /** Max chars of a prior step's result to carry into the next step's prompt. */
    private static final int STORY_RESULT_BUDGET = 4000;

    private final TodoRepository repository;
    private final AttachmentService attachments;

    public TaskPromptBuilder(TodoRepository repository, AttachmentService attachments) {
        this.repository = repository;
        this.attachments = attachments;
    }

    public String buildPrompt(Todo todo) {
        if (todo.isStoryPlanner()) {
            return buildPlannerPrompt(todo);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous worker resolving a single todo item (id ")
                .append(todo.getId()).append(") from a Kanban board.\n");
        sb.append("Do the work needed to complete it, then reply with a short summary of what you did ");
        sb.append("and any result or answer the user needs.\n");
        sb.append("If the task genuinely cannot be completed, end your reply with a line containing ")
                .append("exactly ").append(FAIL_SENTINEL).append(" followed by a brief reason.\n");
        if (todo.isProjectImprovement()) {
            sb.append("If this project has a CLAUDE.md, follow it — it explains how to make changes ")
                    .append("to this project and how to file follow-up tasks.\n\n");
        } else {
            // General task: it is NOT about improving this Kanban app itself. It MAY
            // create/change the RESOURCES it produces — result pages, docs, data files,
            // images — so the work is persisted and reachable. No example directory is
            // named: naming one made it the only filesystem landmark a fresh session had,
            // and tasks piled their output there whether it belonged or not. But it must
            // NOT change the Kanban's OWN CODE (backend, frontend source, build scripts,
            // config), and it must NOT deploy or restart the running website. Only
            // project-improvement tasks change app code and go through the guarded
            // deploy (which restarts the backend); doing so for a general task is
            // needless churn.
            sb.append("This is a GENERAL task — it is NOT about improving this Kanban project itself. ")
                    .append("You MAY create or change RESOURCE/output files that this task produces — for ")
                    .append("example result pages, documents, data files or images — so your work is ")
                    .append("saved and reachable. ")
                    .append("But do NOT modify the Kanban application's OWN CODE (its backend, frontend source, ")
                    .append("build scripts or config), do NOT run scripts/deploy.sh, and do NOT rebuild or ")
                    .append("restart the website or its backend. There is no need to redeploy the app for this ")
                    .append("task. Just do the work the task asks for (using any tools/skills as needed) and ")
                    .append("reply with the result. You may read files, including CLAUDE.md, for context.\n\n");
        }
        appendStoryContext(sb, todo);
        sb.append("TODO TITLE: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("DETAILS:\n").append(todo.getDescription()).append('\n');
        }
        appendAttachments(sb, todo);
        return sb.toString();
    }

    /**
     * If the user attached images to the task, point the agent at the real files.
     * They are stored on this machine by the backend, so the session can open them
     * directly with its Read tool — the picture is part of the task's input, not
     * just decoration on the card.
     */
    private void appendAttachments(StringBuilder sb, Todo todo) {
        if (attachments == null || todo.getId() == null) {
            return;
        }
        List<Attachment> files = attachments.listFor(todo.getId());
        if (files.isEmpty()) {
            return;
        }
        sb.append("\nATTACHED IMAGES (").append(files.size())
                .append("): the user attached the following image file(s) to this task. ")
                .append("They are saved on this machine — open each one with the Read tool ")
                .append("(it renders images) before you start, and treat what they show as part ")
                .append("of the task's instructions:\n");
        for (Attachment a : files) {
            sb.append("  - ").append(attachments.pathOf(a).toString())
                    .append("  (original name: ").append(a.getFileName()).append(")\n");
        }
    }

    /**
     * Prompt for a story planner (see {@link Todo#isStoryPlanner()}). The agent's
     * job is NOT to do the work but to break the goal into concrete step tasks and
     * file each one under the story via the steps endpoint. The steps are queued
     * (PENDING); the worker runs them one at a time, in order.
     */
    private String buildPlannerPrompt(Todo todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are planning a STORY on a Kanban board — a high-level goal that must be ")
                .append("broken down into concrete, independently-workable tasks. Your job is to PLAN, ")
                .append("not to implement: do NOT write code, change files, or resolve the goal yourself.\n\n");
        sb.append("Do this:\n");
        sb.append("1. Consider the goal below. If this project has a CLAUDE.md or the goal is about this ")
                .append("repo, you may read files to understand it — but make no changes.\n");
        sb.append("2. Break the goal into an ordered list of concrete steps (usually 2–6). Each step ")
                .append("should be a single, clearly-scoped task another agent can pick up and finish.\n");
        sb.append("3. Create each step, in order, by POSTing to ")
                .append(API_BASE).append("/api/stories/").append(todo.getStoryId()).append("/steps with a JSON body ")
                .append("{\"title\":\"…\",\"description\":\"…\"} (title ≤200 chars; description = what to do and why). ")
                .append("For example:\n")
                .append("   curl -s -X POST ").append(API_BASE).append("/api/stories/").append(todo.getStoryId())
                .append("/steps -H 'Content-Type: application/json' -d '{\"title\":\"…\",\"description\":\"…\"}'\n");
        sb.append("4. The steps are queued and run automatically in the order you create them (each waits ")
                .append("for the previous step to finish). Do not POST to /api/todos and do not create the ")
                .append("story itself — only add steps to THIS story.\n");
        sb.append("5. Reply with a short summary listing the steps you created.\n");
        sb.append("If the goal cannot be decomposed, end your reply with a line containing exactly ")
                .append(FAIL_SENTINEL).append(" followed by a brief reason.\n\n");
        sb.append("STORY GOAL: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("CONTEXT:\n").append(todo.getDescription()).append('\n');
        }
        return sb.toString();
    }

    /**
     * If the todo is a step in a story, prepend the chain's context: which step
     * this is, and what the earlier steps produced. This is what makes a story a
     * <em>chain</em> — each step builds on the results of the ones before it.
     */
    private void appendStoryContext(StringBuilder sb, Todo todo) {
        if (todo.getStoryId() == null || todo.getStoryOrder() == null) {
            return;
        }
        List<Todo> steps = repository.findByStoryIdOrderByStoryOrderAsc(todo.getStoryId());
        int order = todo.getStoryOrder();
        // Count real steps only — the story's planner seed carries a null order.
        long total = steps.stream().filter(s -> !s.isStoryPlanner()).count();
        sb.append("This task is step ").append(order + 1).append(" of ").append(total)
                .append(" in the story \"").append(todo.getStoryName()).append("\". ")
                .append("The steps run in order and build on each other; do your part so the ")
                .append("next step can continue.\n");
        boolean any = false;
        for (Todo step : steps) {
            if (step.getStoryOrder() == null || step.getStoryOrder() >= order) {
                continue;
            }
            if (!any) {
                sb.append("\nResults of the earlier steps (use them as context):\n");
                any = true;
            }
            sb.append("\n— Step ").append(step.getStoryOrder() + 1)
                    .append(" \"").append(step.getTitle()).append("\":\n");
            String result = step.getResult();
            if (result == null || result.isBlank()) {
                sb.append("(no result recorded)\n");
            } else {
                sb.append(result.length() > STORY_RESULT_BUDGET
                        ? result.substring(0, STORY_RESULT_BUDGET) + "\n…(truncated)\n"
                        : result + "\n");
            }
        }
        sb.append('\n');
    }
}
