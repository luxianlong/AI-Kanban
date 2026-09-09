package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** A workflow's edges — which node's output feeds which node's input. */
public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, Long> {

    List<WorkflowEdge> findByWorkflowId(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
