package com.kanban;

import com.kanban.docs.DocsGitCommitter;
import com.kanban.docs.DocsSiteGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the unauthenticated static-page upload API (task #470). The repo
 * root is a temp directory (worker.working-directory); the docs generator and
 * the git committer are mocked so no node/git subprocess runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PagesApiTest {

    static Path repo;

    @Autowired
    MockMvc mvc;

    @MockBean
    DocsSiteGenerator generator; // stub — don't shell out to node in tests

    @MockBean
    DocsGitCommitter gitCommitter; // stub — don't shell out to git in tests

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        repo = Files.createTempDirectory("kanban-pages-test");
        registry.add("worker.enabled", () -> false);
        registry.add("worker.working-directory", () -> repo.toString());
        // Small enough that a test can exceed it without shipping a megabyte.
        registry.add("pages.max-bytes", () -> 4096);
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:pagestestdb;DB_CLOSE_DELAY=-1");
    }

    Path pagesDir() {
        return repo.resolve("frontend/public/p");
    }

    @BeforeEach
    void clean() throws IOException {
        Path dir = pagesDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void publishesAJsonPageWithNoCredentialsAndReturnsItsPublicUrl() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"title":"Release notes","html":"<h1>Hello</h1>","summary":"what shipped"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("release-notes"))
                .andExpect(jsonPath("$.docId").value("upload-release-notes"))
                .andExpect(jsonPath("$.title").value("Release notes"))
                .andExpect(jsonPath("$.summary").value("what shipped"))
                .andExpect(jsonPath("$.url").value("/p/release-notes.html"));

        assertThat(Files.readString(pagesDir().resolve("release-notes.html"))).isEqualTo("<h1>Hello</h1>");
        assertThat(Files.readString(pagesDir().resolve("release-notes.meta.json")))
                .contains("Release notes");
        // The page only becomes visible once the docs index is rebuilt, and only
        // survives the next deploy once the files are committed.
        verify(generator, times(1)).regenerate();
        verify(gitCommitter, times(1)).commitPaths(anyString(), any());
    }

    @Test
    void acceptsARawHtmlBodyAndTakesTheTitleFromTheDocument() throws Exception {
        mvc.perform(post("/api/pages").contentType(TEXT_HTML)
                        .content("<html><head><title>My  Report</title></head><body>hi</body></html>"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("My Report"))
                .andExpect(jsonPath("$.url").value("/p/my-report.html"));
    }

    @Test
    void acceptsAMultipartFileAndNamesThePageAfterIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "quarterly.html", "text/html", "<h1>Q3</h1>".getBytes());
        mvc.perform(multipart("/api/pages").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("/p/quarterly.html"));
    }

    @Test
    void fallsBackToARandomSlugWhenTheTitleHasNoUrlSafeCharacters() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"title":"美伊战争","html":"<p>分析</p>"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("美伊战争"))
                .andExpect(jsonPath("$.slug").value(matchesPattern("page-[0-9a-f]{8}")))
                .andExpect(jsonPath("$.url").value(startsWith("/p/page-")));
    }

    @Test
    void neverOverwritesAnExistingPage() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"slug":"notes","html":"<p>first</p>"}"""))
                .andExpect(jsonPath("$.slug").value("notes"));
        // An anonymous caller must not be able to replace someone else's page by
        // guessing its slug — the second upload gets its own name.
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"slug":"notes","html":"<p>second</p>"}"""))
                .andExpect(jsonPath("$.slug").value("notes-2"));

        assertThat(Files.readString(pagesDir().resolve("notes.html"))).isEqualTo("<p>first</p>");
        assertThat(Files.readString(pagesDir().resolve("notes-2.html"))).isEqualTo("<p>second</p>");
    }

    @Test
    void sanitisesASlugThatTriesToEscapeThePagesDirectory() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"slug":"../../../etc/passwd","html":"<p>x</p>"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("etc-passwd"));

        assertThat(Files.exists(pagesDir().resolve("etc-passwd.html"))).isTrue();
        assertThat(Files.exists(repo.resolve("etc/passwd"))).isFalse();
    }

    @Test
    void rejectsAnEmptyBodyAndAnOversizedPage() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                        {"title":"nothing","html":"   "}"""))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/pages").contentType(TEXT_HTML).content("x".repeat(5000)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void listsAndFetchesPublishedPages() throws Exception {
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                {"slug":"one","title":"One","html":"<p>1</p>"}"""));
        mvc.perform(post("/api/pages").contentType(APPLICATION_JSON).content("""
                {"slug":"two","title":"Two","html":"<p>2</p>"}"""));

        mvc.perform(get("/api/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/pages/one"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("One"))
                .andExpect(jsonPath("$.url").value("/p/one.html"));
        mvc.perform(get("/api/pages/nope")).andExpect(status().isNotFound());
    }
}
