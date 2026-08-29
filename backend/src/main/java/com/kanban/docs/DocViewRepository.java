package com.kanban.docs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Persistence for {@link DocView} rows — the per-document visit tally (task #523).
 */
public interface DocViewRepository extends JpaRepository<DocView, String> {

    /**
     * Bump an existing tally in one statement, so two concurrent visits can't
     * read-modify-write over each other and lose a hit. Returns the number of
     * rows updated — 0 means this doc has never been visited, and the caller
     * inserts the first row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocView v set v.viewCount = v.viewCount + 1, v.lastViewedAt = :now where v.docId = :id")
    int bump(@Param("id") String id, @Param("now") Instant now);
}
