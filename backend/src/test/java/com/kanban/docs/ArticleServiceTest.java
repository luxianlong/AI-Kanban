package com.kanban.docs;

import com.kanban.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for writing an article from the docs page (task #223). The risky
 * parts are the ones the generator and the filesystem depend on: the slug must
 * stay inside {@code docs/}, an existing article must never be overwritten, and
 * the file must carry an H1 — without one the generated page would publish under
 * its filename instead of the title the author typed.
 */
class ArticleServiceTest {

    private ArticleService service(Path repo) {
        WorkerProperties worker = new WorkerProperties();
        worker.setWorkingDirectory(repo.toString());
        return new ArticleService(worker);
    }

    @Test
    void writesTheMarkdownWhereTheDocsGeneratorPicksItUp() throws IOException {
        Path repo = Files.createTempDirectory("article-write");
        ArticleService.NewArticle article =
                service(repo).write("# My note\n\nSome prose.", null, null);

        assertThat(article.id()).isEqualTo("my-note");
        assertThat(article.title()).isEqualTo("My note");
        assertThat(article.href()).isEqualTo("/docs/my-note.html");
        assertThat(article.file()).isEqualTo("docs/my-note.md");
        assertThat(Files.readString(repo.resolve("docs/my-note.md")))
                .isEqualTo("# My note\n\nSome prose.\n");
    }

    @Test
    void givesTheArticleTheH1ItLacksSoThePageIsTitledNotFilenamed() throws IOException {
        Path repo = Files.createTempDirectory("article-title");
        ArticleService.NewArticle article =
                service(repo).write("正文第一段。", "日本就职冰河期", null);

        // A Chinese title slugifies to nothing, so the URL falls back to a random
        // ASCII name — and the title has to survive in the document itself.
        assertThat(article.id()).startsWith("article-");
        assertThat(article.title()).isEqualTo("日本就职冰河期");
        assertThat(Files.readString(repo.resolve(article.file())))
                .isEqualTo("# 日本就职冰河期\n\n正文第一段。\n");
    }

    @Test
    void neverOverwritesAnExistingArticle() throws IOException {
        Path repo = Files.createTempDirectory("article-collide");
        ArticleService service = service(repo);

        assertThat(service.write("# Note", null, null).id()).isEqualTo("note");
        assertThat(service.write("# Note", null, null).id()).isEqualTo("note-2");
        assertThat(service.write("# Note", null, null).id()).isEqualTo("note-3");
        assertThat(Files.readString(repo.resolve("docs/note.md"))).isEqualTo("# Note\n");
    }

    @Test
    void slugifyStripsEverythingThatCouldEscapeTheDocsDirectory() {
        assertThat(ArticleService.slugify("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(ArticleService.slugify("A Nice Title!")).isEqualTo("a-nice-title");
        assertThat(ArticleService.slugify("Café Déjà Vu")).isEqualTo("cafe-deja-vu");
        assertThat(ArticleService.slugify("日本就职冰河期")).isNull();
        assertThat(ArticleService.slugify("x".repeat(200))).hasSize(64);
    }

    @Test
    void aRequestedSlugIsSanitisedRatherThanTrusted() throws IOException {
        Path repo = Files.createTempDirectory("article-slug");
        ArticleService.NewArticle article =
                service(repo).write("# Note", null, "../../frontend/public/index");

        assertThat(article.id()).isEqualTo("frontend-public-index");
        assertThat(Files.exists(repo.resolve("docs/frontend-public-index.md"))).isTrue();
    }

    @Test
    void refusesAnEmptyOrOversizedArticle() throws IOException {
        Path repo = Files.createTempDirectory("article-limits");
        ArticleService service = service(repo);

        assertThatThrownBy(() -> service.write("   \n ", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.write("#" + "x".repeat(ArticleService.MAX_BYTES), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("413");
    }

    @Test
    void reportsTheFilesBackingAnArticleSoTheDocsDeleteCanRemoveThem() throws IOException {
        Path repo = Files.createTempDirectory("article-paths");
        ArticleService.NewArticle article = service(repo).write("# Note", null, null);

        assertThat(service(repo).pathsFor(article.id())).containsExactly(
                "docs/note.md", "frontend/public/docs/note.html");
    }
}
