package com.kanban;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the "my db is not correct again" regression.
 *
 * The live H2 datasource url in application.yml MUST be an absolute path. A
 * cwd-relative path like {@code jdbc:h2:file:./data/kanban} resolves against the
 * process working directory, which differs by launcher (repo root for
 * {@code java -jar}, backend/ for {@code mvnw spring-boot:run}). That mismatch
 * silently opened two different DB files, so the board appeared to revert after
 * a restart. This test fails if anyone reintroduces a relative path.
 */
class DatasourceUrlConfigTest {

    private String datasourceUrl() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            String yml = new String(Objects.requireNonNull(in, "application.yml on classpath")
                    .readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(yml.split("\n"))
                    .map(String::trim)
                    .filter(l -> l.startsWith("url:") && l.contains("jdbc:h2:file:"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no file-based H2 url in application.yml"));
        }
    }

    @Test
    void datasourceUrlIsAbsoluteNotCwdRelative() throws Exception {
        String url = datasourceUrl();

        // No cwd-relative path segments — those pick a different file per launcher.
        assertThat(url).doesNotContain("file:./");
        assertThat(url).doesNotContain("file:../");
        assertThat(url).doesNotContain("file:data/");

        // The path (after any ${...} placeholder) resolves to an absolute location:
        // either the KANBAN_DATA_DIR override or an absolute ${user.home}-based fallback.
        assertThat(url).contains("KANBAN_DATA_DIR");
        assertThat(url).containsAnyOf("${user.home}/", "file:/");
    }
}
