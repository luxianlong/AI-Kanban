package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** The per-node runs of an execution, in topological (display) order. */
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long> {

    List<NodeExecution> findByExecutionIdOrderByOrderIndexAsc(Long executionId);

    /** Every listed run's nodes in one query, rather than one query per run. */
    List<NodeExecution> findByExecutionIdInOrderByOrderIndexAsc(List<Long> executionIds);

    /** Node runs left in one state — used to clear the ones a restart orphaned. */
    List<NodeExecution> findByStatus(RunState status);
}
