package com.kanban.todo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    /** Ordered oldest-first so the worker processes items fairly (FIFO). */
    List<Todo> findByStatusOrderByCreatedAtAsc(TodoStatus status);

    List<Todo> findAllByOrderByCreatedAtDesc();

    /** All steps of a story, in chain order — used to gate sequential pickup. */
    List<Todo> findByStoryIdOrderByStoryOrderAsc(String storyId);

    /** Every task that belongs to some story, newest chains first. */
    List<Todo> findByStoryIdIsNotNullOrderByCreatedAtDesc();

    /** Every delegated (代理) task, oldest first — the public feed an outside
     * worker polls. The server itself never runs these. */
    List<Todo> findByDelegatedTrueOrderByCreatedAtAsc();

    /** Delegated tasks in one lifecycle state, oldest first (the public feed's
     * {@code ?status=} filter; unclaimed work is the PENDING slice). */
    List<Todo> findByDelegatedTrueAndStatusOrderByCreatedAtAsc(TodoStatus status);

    /** Tasks still held by the async story classifier — used to clear stale holds
     * on startup, since the async classification pass doesn't survive a restart. */
    List<Todo> findByClassifyingTrue();

    /** Clear the unread flag on every finished-task notification in one statement
     * ("mark all read"). Returns how many rows were cleared. */
    @Modifying
    @Transactional
    @Query("update Todo t set t.unread = false where t.unread = true")
    int markAllRead();

    /**
     * Take this task off the queue, but only if it is still on it. Returns 1 if this caller
     * won it and 0 if somebody else already had.
     *
     * <p>A conditional UPDATE rather than read-then-save, because two workers can now claim
     * over HTTP at the same instant and the loser must be able to <em>find out</em> that it
     * lost. Doing this in Java does not work even under a lock: the read and the save sit
     * inside a transaction, so a second thread entering the lock before the first has
     * committed still sees the task as PENDING and claims it too. That is not theoretical —
     * AgentConcurrentClaimTest caught exactly it — and the cost of losing this race is two
     * billed Claude sessions doing the same job, racing to write conflicting results onto one
     * card. The database is the only place the check and the write can be one operation.
     *
     * <p>updatedAt is set explicitly because a bulk update bypasses the entity lifecycle, so
     * the @PreUpdate hook does not run.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Todo t set t.status = com.kanban.todo.TodoStatus.IN_PROGRESS, "
            + "t.processedAt = :now, t.updatedAt = :now, t.attempts = t.attempts + 1 "
            + "where t.id = :id and t.status = com.kanban.todo.TodoStatus.PENDING")
    int claimIfPending(@Param("id") Long id, @Param("now") Instant now);
}
