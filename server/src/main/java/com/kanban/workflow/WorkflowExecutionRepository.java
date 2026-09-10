package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Runs of a workflow, newest first for the executions panel. */
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    List<WorkflowExecution> findAllByOrderByCreatedAtDesc();

    List<WorkflowExecution> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);

    /** How many runs one workflow has, without loading any of them. */
    long countByWorkflowId(String workflowId);

    /** Runs the runner still owes work on, oldest first (FIFO across workflows). */
    List<WorkflowExecution> findByStatusInOrderByCreatedAtAsc(List<RunState> statuses);
}
