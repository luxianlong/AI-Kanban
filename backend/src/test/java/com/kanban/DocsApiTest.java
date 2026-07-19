package com.kanban;

import com.kanban.docs.DocsGitCommitter;
import com.kanban.docs.DocsSiteGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the docs delete API. The static docs site is faked in a temp repo
 * root (pointed at via worker.working-directory); the generator is mocked so no
 * node subprocess runs during the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocsApiTest {

    static Path repo;

    @Autowired
    MockMvc mvc;

    @MockBean
    DocsSiteGenerator generator; // stub — don't shell out to node in tests

    @MockBean
    DocsGitCommitter gitCommitter; // stub — don't shell out to git in tests

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        repo = Files.createTempDirectory("kanban-docs-test");
        registry.add("worker.enabled", () -> false);
        registry.add("worker.working-directory", () -> repo.toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:docstestdb;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void seed() throws IOException {
        // The temp repo root is shared across tests — clear per-doc visibility so
        // each test starts from a clean (all-public) state.
        Files.deleteIfExists(repo.resolve("frontend/.doc-visibility.json"));
        Path docs = Files.createDirectories(repo.resolve("docs"));
        Path pubDocs = Files.createDirectories(repo.resolve("frontend/public/docs"));
        // Plain single-file doc: source .md + generated page.
        Files.writeString(docs.resolve("alpha.md"), "# Alpha\n\nbody\n");
        Files.writeString(pubDocs.resolve("alpha.html"), "<html>alpha</html>");
        // Stitched multi-file report: a whole research/<dir>/ + generated page.
        Path researchDir = Files.createDirectories(repo.resolve("research/report"));
        Files.writeString(researchDir.resolve("01-intro.md"), "# Intro\n");
        Files.writeString(researchDir.resolve("02-body.md"), "# Body\n");
        Files.writeString(pubDocs.resolve("report.html"), "<html>report</html>");
        // Bespoke page: source .md + hand-tuned HTML at the web root + asset dir.
        Files.writeString(docs.resolve("guide.md"), "# Guide\n");
        Files.writeString(repo.resolve("frontend/public/guide.html"), "<html>guide</html>");
        Path assets = Files.createDirectories(repo.resolve("frontend/public/img/guide"));
        Files.writeString(assets.resolve("pic.svg"), "<svg/>");
        // An in-roots source paired with a traversal path, to prove the delete
        // fails closed and leaves even the valid sibling untouched.
        Files.writeString(docs.resolve("evil.md"), "# Evil\n");

        Files.writeString(repo.resolve("frontend/public/docs-manifest.json"),
                "[" +
                "{\"id\":\"alpha\",\"title\":\"Alpha\",\"href\":\"/docs/alpha.html\"," +
                "\"deletable\":true,\"file\":\"docs/alpha.md\"," +
                "\"paths\":[\"docs/alpha.md\",\"frontend/public/docs/alpha.html\"]}," +
                "{\"id\":\"report\",\"title\":\"Report\",\"href\":\"/docs/report.html\"," +
                "\"deletable\":true,\"file\":null," +
                "\"paths\":[\"research/report\",\"frontend/public/docs/report.html\"]}," +
                "{\"id\":\"guide\",\"title\":\"Guide\",\"href\":\"/guide.html\"," +
                "\"deletable\":true,\"file\":\"docs/guide.md\"," +
                "\"paths\":[\"docs/guide.md\",\"frontend/public/guide.html\",\"frontend/public/img/guide\"]}," +
                "{\"id\":\"locked\",\"title\":\"Locked\",\"href\":\"/docs/locked.html\"," +
                "\"deletable\":false,\"file\":null,\"paths\":[]}," +
                "{\"id\":\"evil\",\"title\":\"Evil\",\"href\":\"/docs/evil.html\"," +
                "\"deletable\":true,\"file\":null,\"paths\":[\"docs/evil.md\",\"../../etc/passwd\"]}" +
                "]");
    }

    @Test
    void listReturnsManifest() throws Exception {
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("alpha"))
                .andExpect(jsonPath("$[0].deletable").value(true))
                .andExpect(jsonPath("$[1].id").value("report"))
                .andExpect(jsonPath("$[1].deletable").value(true))
                .andExpect(jsonPath("$[3].id").value("locked"))
                .andExpect(jsonPath("$[3].deletable").value(false));
    }

    @Test
    void deleteRemovesSourceAndGeneratedPage() throws Exception {
        assertThat(Files.exists(repo.resolve("docs/alpha.md"))).isTrue();

        mvc.perform(delete("/api/docs/alpha"))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(repo.resolve("docs/alpha.md"))).isFalse();
        assertThat(Files.exists(repo.resolve("frontend/public/docs/alpha.html"))).isFalse();
        verify(generator, times(1)).regenerate();
        // The removal is persisted to git so a later checkout/merge can't restore it.
        verify(gitCommitter, times(1)).commitDeletion(eq("alpha"),
                eq(java.util.List.of("docs/alpha.md", "frontend/public/docs/alpha.html")));
    }

    @Test
    void deleteStitchedReportRemovesResearchDir() throws Exception {
        assertThat(Files.exists(repo.resolve("research/report/01-intro.md"))).isTrue();

        mvc.perform(delete("/api/docs/report"))
                .andExpect(status().isNoContent());

        // The whole research/<dir>/ tree and the generated page are gone.
        assertThat(Files.exists(repo.resolve("research/report"))).isFalse();
        assertThat(Files.exists(repo.resolve("frontend/public/docs/report.html"))).isFalse();
        verify(generator, times(1)).regenerate();
    }

    @Test
    void deleteBespokePageRemovesSourceHtmlAndAssets() throws Exception {
        mvc.perform(delete("/api/docs/guide"))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(repo.resolve("docs/guide.md"))).isFalse();
        assertThat(Files.exists(repo.resolve("frontend/public/guide.html"))).isFalse();
        assertThat(Files.exists(repo.resolve("frontend/public/img/guide"))).isFalse();
        verify(generator, times(1)).regenerate();
    }

    @Test
    void deleteNonDeletableIsRejected() throws Exception {
        mvc.perform(delete("/api/docs/locked"))
                .andExpect(status().isConflict());

        verify(generator, never()).regenerate();
        verify(gitCommitter, never()).commitDeletion(any(), any());
    }

    @Test
    void deletePathEscapingRootsIsRejectedAndTouchesNothing() throws Exception {
        mvc.perform(delete("/api/docs/evil"))
                .andExpect(status().isBadRequest());

        // Fail-closed: the in-roots path in the same entry must NOT be deleted.
        assertThat(Files.exists(repo.resolve("docs/evil.md"))).isTrue();
        verify(generator, never()).regenerate();
        verify(gitCommitter, never()).commitDeletion(any(), any());
    }

    @Test
    void deleteUnknownIs404() throws Exception {
        mvc.perform(delete("/api/docs/does-not-exist"))
                .andExpect(status().isNotFound());

        verify(generator, never()).regenerate();
    }

    @Test
    void setVisibilityMarksDocPrivateThenPublicAndRegenerates() throws Exception {
        Path visFile = repo.resolve("frontend/.doc-visibility.json");
        assertThat(Files.exists(visFile)).isFalse();

        // Mark 'alpha' private -> persisted to the sidecar, site regenerated.
        mvc.perform(put("/api/docs/alpha/visibility")
                        .contentType(APPLICATION_JSON)
                        .content("{\"private\":true}"))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(visFile)).isTrue();
        assertThat(Files.readString(visFile)).contains("alpha");
        verify(generator, times(1)).regenerate();

        // Flip it back to public -> the id is removed from the private set.
        mvc.perform(put("/api/docs/alpha/visibility")
                        .contentType(APPLICATION_JSON)
                        .content("{\"private\":false}"))
                .andExpect(status().isNoContent());

        assertThat(Files.readString(visFile)).doesNotContain("alpha");
        verify(generator, times(2)).regenerate();
    }

    @Test
    void setVisibilityUnknownDocIs404AndRegeneratesNothing() throws Exception {
        mvc.perform(put("/api/docs/does-not-exist/visibility")
                        .contentType(APPLICATION_JSON)
                        .content("{\"private\":true}"))
                .andExpect(status().isNotFound());

        verify(generator, never()).regenerate();
        assertThat(Files.exists(repo.resolve("frontend/.doc-visibility.json"))).isFalse();
    }

    @Test
    void docReadsStartEmptyAndPersistWhatIsMarkedSeen() throws Exception {
        // Nothing seen yet — the badge would count every doc as new.
        mvc.perform(get("/api/docs/reads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Marking two docs seen persists them (server-side, so it's shared across
        // devices); blank ids are ignored.
        mvc.perform(post("/api/docs/reads")
                        .contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"alpha\",\"report\",\"\"]}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/docs/reads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$", containsInAnyOrder("alpha", "report")));

        // Idempotent: re-marking an already-seen doc plus a new one doesn't dup.
        mvc.perform(post("/api/docs/reads")
                        .contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"alpha\",\"guide\"]}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/docs/reads"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$", containsInAnyOrder("alpha", "report", "guide")));
    }
}
