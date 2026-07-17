package com.kanban.todo;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Story} rows — the story containers that survive with no
 * tasks. Steps still live on {@link TodoRepository}; this only tracks the story's
 * identity and name so an empty chain keeps existing until it's deleted.
 */
public interface StoryRepository extends JpaRepository<Story, String> {
}
