package com.kanban.workflow;

import com.kanban.workflow.WorkflowDtos.EditNodePromptRequest;
import com.kanban.workflow.WorkflowDtos.EditNodeRunRequest;
import com.kanban.workflow.WorkflowDtos.ExecutionCountView;
import com.kanban.workflow.WorkflowDtos.ExecutionView;
import com.kanban.workflow.WorkflowDtos.FavoriteStepView;
import com.kanban.workflow.WorkflowDtos.InsertNodeRunRequest;
import com.kanban.workflow.WorkflowDtos.RunWorkflowRequest;
import com.kanban.workflow.WorkflowDtos.SaveFavoriteStepRequest;
import com.kanban.workflow.WorkflowDtos.SaveWorkflowRequest;
import com.kanban.workflow.WorkflowDtos.WorkflowView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for workflows — a DAG of prompt tasks — and their executions.
 *
 * <p>Both halves live under one path because the UI shows them on one page: the
 * graph you edit on the left, the runs it produced on the right.
 */
@RestController
@RequestMapping("/api/workflows")
@CrossOrigin(origins = "*")
public class WorkflowController {

    private final WorkflowService service;
    private final FavoriteStepService favorites;

    public WorkflowController(WorkflowService service, FavoriteStepService favorites) {
        this.service = service;
        this.favorites = favorites;
    }

    @GetMapping
    public List<WorkflowView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowView create(@Valid @RequestBody SaveWorkflowRequest req) {
        return service.create(req);
    }

    /**
     * Runs of every workflow, newest first — or of one workflow with {@code ?workflowId=}.
     *
     * <p>Mapped before {@code /{id}} so "executions" is never mistaken for a workflow id.
     */
    @GetMapping("/executions")
    public List<ExecutionView> executions(@RequestParam(required = false) String workflowId) {
        return service.listExecutions(workflowId);
    }

    /**
     * How many runs that same scope holds — the honest total, not the capped page.
     * Asked before deleting a workflow, so the confirm can name what goes with it.
     */
    @GetMapping("/executions/count")
    public ExecutionCountView executionCount(@RequestParam(required = false) String workflowId) {
        return service.countExecutions(workflowId);
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionView execution(@PathVariable Long executionId) {
        return service.getExecution(executionId);
    }

    /** Stop a run in flight: kills the live node session and closes the run out. */
    @PostMapping("/executions/{executionId}/cancel")
    public ExecutionView cancel(@PathVariable Long executionId) {
        return service.cancel(executionId);
    }

    /**
     * Queue a finished run again from the step that broke. The steps that succeeded
     * keep their output; the failed one and everything blocked behind it run afresh.
     */
    @PostMapping("/executions/{executionId}/retry")
    public ExecutionView retry(@PathVariable Long executionId) {
        return service.retry(executionId);
    }

    @DeleteMapping("/executions/{executionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExecution(@PathVariable Long executionId) {
        service.deleteExecution(executionId);
    }

    /**
     * Run one step of a finished run again — even one that succeeded. By default every
     * step downstream of it goes too, since its output is their input; pass
     * {@code ?downstream=false} to redo just that one step and leave the rest of the
     * run as it is. The steps above it keep their output either way. 409 if the run is
     * still going, if the entry never reached that step, or — for the single-step form
     * only — if the step it reads from didn't succeed, leaving it no input.
     */
    @PostMapping("/node-runs/{nodeRunId}/retry")
    public ExecutionView retryNode(@PathVariable Long nodeRunId,
                                   @RequestParam(defaultValue = "true") boolean downstream) {
        return service.retryNode(nodeRunId, downstream);
    }

    /**
     * Start one step that is waiting to be told to go — the play button on a paused run.
     *
     * <p>A step marked "manual start" in the editor holds the run up even once everything
     * feeding it has succeeded, so that somebody can read what came out above before the
     * next session is paid for. This releases exactly one such step; a worker picks it up
     * on its next tick, and the run carries on by itself from there until the next one.
     *
     * <p>409, with a sentence saying which it is, when the step is not waiting for
     * anybody: the run has finished, the step isn't a manual one, it has already been
     * released, or its input hasn't arrived yet.
     */
    @PostMapping("/node-runs/{nodeRunId}/start")
    public ExecutionView startNode(@PathVariable Long nodeRunId) {
        return service.startNode(nodeRunId);
    }

    /**
     * Rewrite one step's prompt inside this run only — the workflow keeps its own text.
     * Pair it with the re-run above to try a wording for the price of one session
     * instead of a whole graph. 409 while the run is live, or if the entry never
     * reached that step (there would be no way to test the change).
     */
    @PutMapping("/node-runs/{nodeRunId}/prompt")
    public ExecutionView editNodePrompt(@PathVariable Long nodeRunId,
                                        @Valid @RequestBody EditNodePromptRequest req) {
        return service.editNodePrompt(nodeRunId, req.prompt());
    }

    /**
     * Rewrite one step of this run completely — name, prompt, effort and what feeds it.
     * The workflow keeps its own copy of all four, so this changes what <em>this</em> run
     * would do on a re-run and nothing else.
     *
     * <p>The wider form of the prompt endpoint above, and the reason a run can be
     * debugged rather than only re-worded: the two questions a disappointing step raises
     * are usually "was this worth the effort level it got" and "should it have been
     * reading the step above as well", and neither is a change of wording.
     *
     * <p>409 in the same two cases as the prompt endpoint (run live, step never
     * reached); 400 for a level that isn't one, or wiring that would loop, dangle, or cut
     * the step off from the step the run started at.
     */
    @PutMapping("/node-runs/{nodeRunId}")
    public ExecutionView editNodeRun(@PathVariable Long nodeRunId,
                                     @Valid @RequestBody EditNodeRunRequest req) {
        return service.editNodeRun(nodeRunId, req);
    }

    /**
     * Add a step to a finished run — one the workflow never had. It lands queued and
     * nothing starts; the step's own re-run button is what spends a session on it.
     *
     * <p>{@code inputs} must name at least one step already in the run. 409 while the run
     * is live or once it holds as many steps as a workflow may.
     */
    @PostMapping("/executions/{executionId}/node-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionView insertNodeRun(@PathVariable Long executionId,
                                       @Valid @RequestBody InsertNodeRunRequest req) {
        return service.insertNodeRun(executionId, req);
    }

    /**
     * Take a step out of this run, splicing the chain shut behind it — whatever read the
     * removed step now reads what it read. Its output is destroyed with it.
     *
     * <p>Returns the run rather than 204: removing a step rewires and renumbers the ones
     * around it, so the answer is the whole run as it now stands. 409 while the run is
     * live, on the step the run started at, and on the last step left.
     */
    @DeleteMapping("/node-runs/{nodeRunId}")
    public ExecutionView deleteNodeRun(@PathVariable Long nodeRunId) {
        return service.deleteNodeRun(nodeRunId);
    }

    /** Live transcript of one node's Claude session — poll it while the node runs. */
    @GetMapping("/node-runs/{nodeRunId}/log")
    public Map<String, Object> nodeLog(@PathVariable Long nodeRunId) {
        return service.nodeLog(nodeRunId);
    }

    // --- favourite steps -------------------------------------------------
    // Steps kept aside to be used again, saved from one workflow's editor and
    // inserted into another's. They belong to no workflow, which is why they hang
    // off this path rather than under /{id}: a favourite outlives the graph it was
    // written in. Mapped before /{id} for the same reason /executions is.

    @GetMapping("/favorites")
    public List<FavoriteStepView> favorites() {
        return favorites.list();
    }

    /**
     * Keep a step for later. Saving under a name that is already on the shelf
     * <em>replaces</em> that favourite (case-insensitively) rather than adding a
     * second one beside it — so the answer is 200 with the favourite as it now
     * stands, not 201, since either outcome is possible.
     */
    @PostMapping("/favorites")
    public FavoriteStepView saveFavorite(@Valid @RequestBody SaveFavoriteStepRequest req) {
        return favorites.save(req);
    }

    /** Take a step off the shelf. Workflows built from it are untouched — it was a copy. */
    @DeleteMapping("/favorites/{favoriteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFavorite(@PathVariable String favoriteId) {
        favorites.delete(favoriteId);
    }

    @GetMapping("/{id}")
    public WorkflowView get(@PathVariable String id) {
        return service.get(id);
    }

    /** Replace a workflow's name, description and whole graph in one write. */
    @PutMapping("/{id}")
    public WorkflowView replace(@PathVariable String id, @Valid @RequestBody SaveWorkflowRequest req) {
        return service.replace(id, req);
    }

    /**
     * Copy a workflow's graph into a new workflow — the copy starts with no runs.
     * Returns the copy, which is what the page then opens for editing.
     */
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowView duplicate(@PathVariable String id) {
        return service.duplicate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** Queue a run. The runner picks it up on its next tick. */
    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionView run(@PathVariable String id,
                             @Valid @RequestBody(required = false) RunWorkflowRequest req) {
        return service.run(id, req == null ? null : req.input());
    }
}
