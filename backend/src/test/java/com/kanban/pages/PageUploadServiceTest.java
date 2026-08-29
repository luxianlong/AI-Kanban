package com.kanban.pages;

import com.kanban.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the caps that stand in for authentication on the public upload
 * API (task #470) — nobody signs in, so the rate/count limits are the only thing
 * keeping an anonymous caller from filling the disk.
 */
class PageUploadServiceTest {

    private PageUploadService service(Path repo, PagesProperties props) {
        WorkerProperties worker = new WorkerProperties();
        worker.setWorkingDirectory(repo.toString());
        return new PageUploadService(worker, props);
    }

    @Test
    void refusesMoreThanTheHourlyQuotaFromOneClient() throws IOException {
        Path repo = Files.createTempDirectory("pages-rate");
        PagesProperties props = new PagesProperties();
        props.setMaxUploadsPerHour(2);
        PageUploadService service = service(repo, props);

        service.store("<p>1</p>", "a", null, null, "1.2.3.4");
        service.store("<p>2</p>", "b", null, null, "1.2.3.4");
        assertThatThrownBy(() -> service.store("<p>3</p>", "c", null, null, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        // A different caller still has its own quota.
        assertThat(service.store("<p>4</p>", "d", null, null, "5.6.7.8").slug()).isEqualTo("d");
    }

    @Test
    void refusesUploadsOnceTheBoardHoldsTheMaximumNumberOfPages() throws IOException {
        Path repo = Files.createTempDirectory("pages-max");
        PagesProperties props = new PagesProperties();
        props.setMaxPages(1);
        PageUploadService service = service(repo, props);

        service.store("<p>1</p>", "only", null, null, "1.2.3.4");
        assertThatThrownBy(() -> service.store("<p>2</p>", "more", null, null, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void slugifyStripsEverythingThatCouldEscapeThePagesDirectory() {
        assertThat(PageUploadService.slugify("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(PageUploadService.slugify("A Nice Title!")).isEqualTo("a-nice-title");
        assertThat(PageUploadService.slugify("Café Déjà Vu")).isEqualTo("cafe-deja-vu");
        assertThat(PageUploadService.slugify("美伊战争")).isNull();
        assertThat(PageUploadService.slugify("x".repeat(200))).hasSize(64);
    }

    @Test
    void injectsTheMermaidRendererIntoAPageThatDrawsDiagrams() throws IOException {
        Path repo = Files.createTempDirectory("pages-mermaid");
        PageUploadService service = service(repo, new PagesProperties());

        service.store("<html><body><pre class=\"mermaid\">graph TD; A--&gt;B;</pre></body></html>",
                "ddd", null, null, "1.2.3.4");

        String stored = Files.readString(repo.resolve("frontend/public/p/ddd.html"));
        assertThat(stored).contains("/vendor/mermaid.min.js", "/vendor/mermaid-init.js");
        // Injected inside the document, before </body>, so the diagram is parsed
        // by the time the deferred scripts run.
        assertThat(stored.indexOf("mermaid-init.js")).isLessThan(stored.indexOf("</body>"));
        assertThat(stored).contains("graph TD; A--&gt;B;");
    }

    @Test
    void leavesPagesWithoutMermaidOrWithTheirOwnCopyUntouched() {
        String plain = "<html><body><p>No diagrams here.</p></body></html>";
        assertThat(PageUploadService.withMermaidSupport(plain)).isEqualTo(plain);

        String own = "<body><div class='mermaid'>graph TD; A-->B;</div>"
                + "<script src='https://cdn.example/mermaid.min.js'></script></body>";
        assertThat(PageUploadService.withMermaidSupport(own)).isEqualTo(own);

        // Markdown-rendered fences count as diagrams too, and a page with no
        // </body> still gets the tags (appended at the end).
        String fenced = "<code class=\"language-mermaid\">graph TD; A-->B;</code>";
        assertThat(PageUploadService.withMermaidSupport(fenced))
                .startsWith(fenced)
                .contains("/vendor/mermaid-init.js");
    }

    @Test
    void reportsTheFilesBackingAPageSoTheDocsDeleteCanRemoveThem() throws IOException {
        Path repo = Files.createTempDirectory("pages-paths");
        PageUploadService service = service(repo, new PagesProperties());
        service.store("<p>x</p>", "report", null, null, "1.2.3.4");

        assertThat(service.pathsFor("report")).containsExactly(
                "frontend/public/p/report.html", "frontend/public/p/report.meta.json");
        assertThat(service.pathsFor("report"))
                .allSatisfy(rel -> assertThat(Files.exists(repo.resolve(rel))).isTrue());
    }
}
