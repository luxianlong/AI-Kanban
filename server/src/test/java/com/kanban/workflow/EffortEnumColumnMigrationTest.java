package com.kanban.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The bug this pins is one the rest of the suite structurally cannot see.
 *
 * <p>Every other test runs against a fresh in-memory H2 whose schema Hibernate generates
 * from today's {@link EffortLevel}, so its {@code effort} column is created already
 * permitting every constant. Production's H2 file was created when the enum had five
 * constants and {@code ddl-auto: update} never revisits an existing column's type, so
 * there the column still refused {@code ULTRACODE} — the picker offered a level that
 * could not be saved, and {@code POST /api/workflows} answered 500.
 *
 * <p>So these tests deliberately build the <em>old</em> column by hand rather than letting
 * Hibernate build a correct one. Each uses its own in-memory database; none touches the
 * board's file database, and no Spring context (and therefore no worker) is started.
 */
class EffortEnumColumnMigrationTest {

    /** The column definition as it exists in a database created before ultracode. */
    private static final String OLD_ENUM = "ENUM('HIGH','LOW','MAX','MEDIUM','XHIGH')";

    private JdbcTemplate freshDatabase() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:effortmig-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(ds);
    }

    private void createLegacyTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE workflow_nodes (id VARCHAR(36), effort " + OLD_ENUM + ")");
        jdbc.execute("CREATE TABLE workflow_node_executions (id BIGINT, effort " + OLD_ENUM + ")");
    }

    private void migrate(JdbcTemplate jdbc) {
        // The EntityManagerFactory argument exists only to order the bean after Hibernate's
        // schema export; the migration never calls it, so a null stands in here.
        new EffortEnumColumnMigration(jdbc, null).migrate();
    }

    @Test
    void aColumnCreatedBeforeUltracodeExistedCannotStoreIt() {
        JdbcTemplate jdbc = freshDatabase();
        createLegacyTables(jdbc);

        // The production failure, reproduced: this is what the 500 was.
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO workflow_nodes VALUES ('n1', 'ULTRACODE')"))
                .hasMessageContaining("ULTRACODE");

        // ...while every pre-existing level saves fine, which is why the bug looked like it
        // was about ultracode specifically rather than about the schema.
        assertThatCode(() -> jdbc.update("INSERT INTO workflow_nodes VALUES ('n2', 'MAX')"))
                .doesNotThrowAnyException();
    }

    @Test
    void theMigrationWidensBothEffortColumnsSoUltracodeCanBeStored() {
        JdbcTemplate jdbc = freshDatabase();
        createLegacyTables(jdbc);

        migrate(jdbc);

        // Both tables, not just the one the editor writes: the node execution is the
        // snapshot taken when a step starts, so widening only workflow_nodes would accept
        // the workflow and then fail the run.
        assertThatCode(() -> {
            jdbc.update("INSERT INTO workflow_nodes VALUES ('n1', 'ULTRACODE')");
            jdbc.update("INSERT INTO workflow_node_executions VALUES (1, 'ULTRACODE')");
        }).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT effort FROM workflow_nodes WHERE id = 'n1'", String.class))
                .isEqualTo("ULTRACODE");
    }

    @Test
    void everyLevelTheEnumDeclaresCanBeStoredAfterMigrating() {
        JdbcTemplate jdbc = freshDatabase();
        createLegacyTables(jdbc);

        migrate(jdbc);

        // Written against the enum rather than a hand-listed set, so a constant added later
        // fails here unless the migration carries it too.
        for (EffortLevel level : EffortLevel.values()) {
            assertThatCode(() -> jdbc.update(
                    "INSERT INTO workflow_nodes VALUES (?, ?)", level.name(), level.name()))
                    .as("level %s should be storable", level)
                    .doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForList("SELECT effort FROM workflow_nodes", String.class))
                .containsExactlyInAnyOrderElementsOf(
                        List.of(EffortLevel.values()).stream().map(Enum::name).toList());
    }

    @Test
    void wideningKeepsTheRowsAlreadyStored() {
        JdbcTemplate jdbc = freshDatabase();
        createLegacyTables(jdbc);
        jdbc.update("INSERT INTO workflow_nodes VALUES ('kept-low', 'LOW')");
        jdbc.update("INSERT INTO workflow_nodes VALUES ('kept-max', 'MAX')");
        jdbc.update("INSERT INTO workflow_nodes VALUES ('kept-null', NULL)");

        migrate(jdbc);

        // Null is a real value for effort ("leave this step at the CLI's default"), so it
        // has to survive the rewrite as much as the set levels do.
        assertThat(jdbc.queryForObject(
                "SELECT effort FROM workflow_nodes WHERE id = 'kept-low'", String.class)).isEqualTo("LOW");
        assertThat(jdbc.queryForObject(
                "SELECT effort FROM workflow_nodes WHERE id = 'kept-max'", String.class)).isEqualTo("MAX");
        assertThat(jdbc.queryForObject(
                "SELECT effort FROM workflow_nodes WHERE id = 'kept-null'", String.class)).isNull();
    }

    @Test
    void runningItTwiceChangesNothingTheSecondTime() {
        JdbcTemplate jdbc = freshDatabase();
        createLegacyTables(jdbc);

        migrate(jdbc);
        assertThatCode(() -> migrate(jdbc)).doesNotThrowAnyException();

        assertThatCode(() -> jdbc.update("INSERT INTO workflow_nodes VALUES ('n1', 'ULTRACODE')"))
                .doesNotThrowAnyException();
    }

    @Test
    void aDatabaseWithNoWorkflowTablesYetIsLeftAlone() {
        // A brand-new install: Hibernate creates the column correct from today's enum, so
        // there is nothing to widen and the migration must not fail startup over it.
        assertThatCode(() -> migrate(freshDatabase())).doesNotThrowAnyException();
    }

    @Test
    void aValueTheDatabaseHasButJavaNoLongerDoesIsNotDroppedFromTheColumn() {
        JdbcTemplate jdbc = freshDatabase();
        jdbc.execute("CREATE TABLE workflow_nodes (id VARCHAR(36), effort ENUM('LOW','LEGACY'))");
        jdbc.execute("CREATE TABLE workflow_node_executions (id BIGINT, effort ENUM('LOW','LEGACY'))");
        jdbc.update("INSERT INTO workflow_nodes VALUES ('old', 'LEGACY')");

        migrate(jdbc);

        // Removing a constant is a data migration (see LegacyStatusMigration), not this
        // one's job — dropping it here would make the stored row unreadable.
        assertThat(jdbc.queryForObject(
                "SELECT effort FROM workflow_nodes WHERE id = 'old'", String.class)).isEqualTo("LEGACY");
        assertThatCode(() -> jdbc.update("INSERT INTO workflow_nodes VALUES ('new', 'ULTRACODE')"))
                .doesNotThrowAnyException();
    }
}
