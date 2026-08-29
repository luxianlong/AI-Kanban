package com.kanban.todo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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
}
