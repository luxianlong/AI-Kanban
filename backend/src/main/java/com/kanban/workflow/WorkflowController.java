package com.kanban.workflow;

import com.kanban.workflow.WorkflowDtos.ExecutionView;
import com.kanban.workflow.WorkflowDtos.RunWorkflowRequest;
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

    public WorkflowController(WorkflowService service) {
        this.service = service;
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

    @GetMapping("/executions/{executionId}")
    public ExecutionView execution(@PathVariable Long executionId) {
        return service.getExecution(executionId);
    }

    /** Stop a run in flight: kills the live node session and closes the run out. */
    @PostMapping("/executions/{executionId}/cancel")
    public ExecutionView cancel(@PathVariable Long executionId) {
        return service.cancel(executionId);
    }

    @DeleteMapping("/executions/{executionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExecution(@PathVariable Long executionId) {
        service.deleteExecution(executionId);
    }

    /** Live transcript of one node's Claude session — poll it while the node runs. */
    @GetMapping("/node-runs/{nodeRunId}/log")
    public Map<String, Object> nodeLog(@PathVariable Long nodeRunId) {
        return service.nodeLog(nodeRunId);
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
