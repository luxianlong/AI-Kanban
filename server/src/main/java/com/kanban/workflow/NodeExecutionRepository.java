package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** The per-node runs of an execution, in topological (display) order. */
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, Long> {

    List<NodeExecution> findByExecutionIdOrderByOrderIndexAsc(Long executionId);

    /** Every listed run's nodes in one query, rather than one query per run. */
    List<NodeExecution> findByExecutionIdInOrderByOrderIndexAsc(List<Long> executionIds);

    /** Node runs left in one state — used to clear the ones a restart orphaned. */
    List<NodeExecution> findByStatus(RunState status);

    /** Node runs of these executions in one of these states — used to tell which runs
     *  already have something in flight. */
    List<NodeExecution> findByExecutionIdInAndStatusIn(List<Long> executionIds, List<RunState> statuses);

    /**
     * Start this node, but only if nobody else has. Returns 1 if this caller won it.
     *
     * <p>The in-process runner never needed this: one scheduler thread dispatched, an
     * execution occupied one lane, and so exactly one thread ever touched a run's node rows —
     * which is why there is no version column here. Remote workers claim over HTTP on whatever
     * threads are free, so the claim has to become a single operation the loser can detect.
     * See TodoRepository.claimIfPending for the same reasoning and the same shape.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update NodeExecution n set n.status = com.kanban.workflow.RunState.RUNNING, "
            + "n.startedAt = :now, n.input = :input "
            + "where n.id = :id and n.status = com.kanban.workflow.RunState.PENDING")
    int claimIfPending(@Param("id") Long id, @Param("now") Instant now, @Param("input") String input);
}
