package com.kanban.docs;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link DocRead} rows — which documents the user has already
 * opened. Backs the cross-device "new docs" badge: a doc whose id has no row
 * here is still unseen.
 */
public interface DocReadRepository extends JpaRepository<DocRead, String> {
}
