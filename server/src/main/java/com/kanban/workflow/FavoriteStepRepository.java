package com.kanban.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Favourited steps, listed alphabetically: the list is a shelf to find something on,
 * not a feed, and a name is what the user is looking for it by. (Workflows and runs
 * are newest-first for the opposite reason — those are about what just happened.)
 */
public interface FavoriteStepRepository extends JpaRepository<FavoriteStep, String> {

    List<FavoriteStep> findAllByOrderByNameAsc();

    /**
     * Case-insensitive, because "Summarise" and "summarise" are the same shelf label to
     * everyone except the database — and saving both would leave the picker showing two
     * entries the user cannot tell apart.
     */
    Optional<FavoriteStep> findFirstByNameIgnoreCase(String name);
}
