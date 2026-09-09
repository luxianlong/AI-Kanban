package com.kanban.workflow;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Teaches the live database about {@link EffortLevel} constants added after its schema
 * was first created.
 *
 * <p><strong>Why this is needed at all.</strong> {@code effort} is mapped
 * {@code @Enumerated(EnumType.STRING)}, and Hibernate's H2 dialect renders that as H2's
 * <em>native</em> {@code ENUM('HIGH','LOW','MAX','MEDIUM','XHIGH')} column type — the
 * permitted values are baked into the column definition. {@code ddl-auto: update} adds
 * missing tables and columns but never rewrites the definition of a column that already
 * exists, so adding a constant to the Java enum leaves the database still refusing it.
 *
 * <p>That is not a theoretical gap. When {@link EffortLevel#ULTRACODE} was added, the
 * board's own H2 file still declared the original five, so saving a workflow with a step
 * set to ultracode was accepted by validation and then failed at the insert — a 500 from
 * {@code POST /api/workflows}, with an identical payload succeeding for {@code max}. The
 * picker offered a value that could not be stored.
 *
 * <p>The unit tests did not catch it because they run against a fresh in-memory H2 whose
 * schema is generated from today's enum, so the column is created correct and the
 * mismatch cannot arise. Only a database that predates the constant is affected, which is
 * exactly the production one.
 *
 * <p><strong>Why it is written against the enum rather than against ultracode.</strong>
 * The value list is derived from {@link EffortLevel#values()}, so the next constant added
 * is carried automatically instead of shipping the same bug again. The migration compares
 * what the column declares with what Java declares and only issues DDL when something is
 * missing, so a healthy database — including every freshly created one — does no work.
 *
 * <p>Widening an H2 enum preserves the rows already stored (the previous values all remain
 * in the list), and no row can hold a value being added, so this cannot lose data. It is
 * idempotent: the second startup finds nothing missing.
 *
 * @see LegacyStatusMigration in the todo package, the same idea for a removed constant
 */
@Component
public class EffortEnumColumnMigration {

    private static final Logger log = LoggerFactory.getLogger(EffortEnumColumnMigration.class);

    /**
     * Every table carrying an {@link EffortLevel} column. Both matter: the workflow node
     * is what the editor saves, and the node execution is the per-run snapshot taken when
     * a step starts — a database that widened only the first would accept the workflow and
     * then fail the run.
     */
    private static final List<String> TABLES = List.of("workflow_nodes", "workflow_node_executions");

    private static final String COLUMN = "effort";

    private final JdbcTemplate jdbc;

    /**
     * The {@link EntityManagerFactory} is injected purely for ordering: constructing it is
     * what runs Hibernate's schema export, so depending on it means the tables exist by the
     * time we look for them rather than depending on bean-creation order.
     */
    EffortEnumColumnMigration(JdbcTemplate jdbc, EntityManagerFactory ignoredButOrdersUsAfterSchemaExport) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        for (String table : TABLES) {
            try {
                widen(table);
            } catch (Exception e) {
                // A brand-new database (no such table yet), or a dialect that stores the
                // enum as plain text and so has nothing to widen. Both are safe no-ops:
                // in either case the column can already hold every constant.
                log.debug("Effort enum widening skipped for {}: {}", table, e.toString());
            }
        }
    }

    private void widen(String table) {
        Set<String> declared = declaredValues(table);
        if (declared.isEmpty()) {
            return; // Not a native enum column — nothing constrains the value.
        }
        Set<String> wanted = Arrays.stream(EffortLevel.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(declared);
        if (missing.isEmpty()) {
            return;
        }

        // Keep any value the column already declares even if Java no longer has it: dropping
        // it would make the stored rows unreadable, which is a different migration's job.
        Set<String> merged = new LinkedHashSet<>(wanted);
        merged.addAll(declared);
        String values = merged.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));

        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + COLUMN + " ENUM (" + values + ")");
        log.info("Widened {}.{} to accept {} (added {}).", table, COLUMN, merged, missing);
    }

    /** The values the column's own type permits, empty if it is not a native enum. */
    private Set<String> declaredValues(String table) {
        List<String> values = jdbc.queryForList(
                "SELECT e.VALUE_NAME FROM INFORMATION_SCHEMA.ENUM_VALUES e "
                        + "JOIN INFORMATION_SCHEMA.COLUMNS c "
                        + "  ON c.TABLE_NAME = e.OBJECT_NAME "
                        + " AND c.TABLE_SCHEMA = e.OBJECT_SCHEMA "
                        + " AND c.DTD_IDENTIFIER = e.ENUM_IDENTIFIER "
                        + "WHERE c.TABLE_NAME = ? AND c.COLUMN_NAME = ?",
                String.class,
                table.toUpperCase(Locale.ROOT),
                COLUMN.toUpperCase(Locale.ROOT));
        return new LinkedHashSet<>(values);
    }
}
