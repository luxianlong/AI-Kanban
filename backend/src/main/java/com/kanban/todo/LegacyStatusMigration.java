package com.kanban.todo;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot data migration for the removal of the legacy {@code NEW} ("staged")
 * status. Rows in the file-based H2 database that were persisted as
 * {@code status = 'NEW'} would now fail enum deserialization (there is no
 * {@link TodoStatus#PENDING NEW} constant anymore), breaking any read that
 * returns them. This rewrites them to {@code PENDING} with raw SQL — bypassing
 * the enum mapping — at startup, before any JPA read touches them, so those tasks
 * simply land in the To Do queue. It's a no-op (and harmless) once none remain.
 */
@Component
public class LegacyStatusMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyStatusMigration.class);

    private final JdbcTemplate jdbc;

    LegacyStatusMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        try {
            int moved = jdbc.update("UPDATE todos SET status = 'PENDING' WHERE status = 'NEW'");
            if (moved > 0) {
                log.info("Migrated {} legacy NEW todo(s) to PENDING (the NEW status was removed).", moved);
            }
        } catch (Exception e) {
            // Nothing to migrate: either the todos table doesn't exist yet (a brand
            // new database) or the schema no longer permits the NEW literal at all
            // (so no NEW row could ever have been stored). Both are safe no-ops.
            log.debug("Legacy NEW->PENDING migration skipped: {}", e.toString());
        }
    }
}
