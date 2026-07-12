package com.dashboard.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    /** Ordered oldest-first so the worker processes items fairly (FIFO). */
    List<Todo> findByStatusOrderByCreatedAtAsc(TodoStatus status);

    List<Todo> findAllByOrderByCreatedAtDesc();

    /** All steps of a story, in chain order — used to gate sequential pickup. */
    List<Todo> findByStoryIdOrderByStoryOrderAsc(String storyId);

    /** Every task that belongs to some story, newest chains first. */
    List<Todo> findByStoryIdIsNotNullOrderByCreatedAtDesc();
}
