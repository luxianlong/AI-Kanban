package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * A workflow's nodes. Ordered by the surrogate key, which is insertion order — so a
 * graph always comes back in the order it was saved rather than whatever order the
 * database felt like, and reloading the editor doesn't shuffle the step cards.
 */
public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, Long> {

    List<WorkflowNode> findByWorkflowIdOrderByIdAsc(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
