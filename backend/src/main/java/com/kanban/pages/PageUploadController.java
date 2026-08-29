package com.kanban.pages;

import com.kanban.docs.DocsGitCommitter;
import com.kanban.docs.DocsSiteGenerator;
import com.kanban.pages.PageUploadService.StoredPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Publish a static web page to the board — no authentication required
 * (task #470).
 *
 * <p>Anyone who can reach the board can POST one self-contained HTML file here
 * and immediately get back a public URL for it:
 *
 * <pre>
 *   # raw file (most convenient with curl)
 *   curl -X POST 'http://localhost:8080/api/pages?title=My%20page' \
 *        -H 'Content-Type: text/html' --data-binary @index.html
 *
 *   # JSON
 *   curl -X POST http://localhost:8080/api/pages -H 'Content-Type: application/json' \
 *        -d '{"title":"My page","html":"&lt;h1&gt;hi&lt;/h1&gt;"}'
 *
 *   # browser form / multipart
 *   curl -X POST http://localhost:8080/api/pages -F 'file=@index.html'
 * </pre>
 *
 * <p>The response carries the page's public URL ({@code /p/<slug>.html}). That
 * path is exempt from the site password (see {@code frontend/docsGate.mjs}), and
 * the upload re-runs the docs-site generator so the new page also appears as a
 * card on the public {@code /docs.html} index — that is how an uploaded page is
 * shown to the public. See {@code docs/publish-static-page.md}.
 *
 * <p>Writing is anonymous; <em>removing</em> a page is not. Deletion goes
 * through the owner-only {@code DELETE /api/docs/{docId}} behind the auth gate,
 * so a stranger can publish but never unpublish.
 */
@RestController
@RequestMapping("/api/pages")
@CrossOrigin(origins = "*")
public class PageUploadController {

    private final PageUploadService service;
    private final DocsSiteGenerator generator;
    private final DocsGitCommitter gitCommitter;

    public PageUploadController(PageUploadService service, DocsSiteGenerator generator,
                                DocsGitCommitter gitCommitter) {
        this.service = service;
        this.generator = generator;
        this.gitCommitter = gitCommitter;
    }

    /** JSON body: {@code {"html": "...", "title": "...", "slug": "...", "summary": "..."}}. */
    public record UploadRequest(String html, String title, String slug, String summary) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredPage uploadJson(@RequestBody UploadRequest req, HttpServletRequest http) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing request body");
        }
        return publish(req.html(), req.slug(), req.title(), req.summary(), http);
    }

    /** Raw HTML body; title/slug/summary ride along as query parameters. */
    @PostMapping(consumes = {MediaType.TEXT_HTML_VALUE, MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    public StoredPage uploadRaw(@RequestBody byte[] body,
                                @RequestParam(required = false) String title,
                                @RequestParam(required = false) String slug,
                                @RequestParam(required = false) String summary,
                                HttpServletRequest http) {
        return publish(new String(body, StandardCharsets.UTF_8), slug, title, summary, http);
    }

    /** Multipart upload, so a plain HTML {@code <form>} can publish a page too. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredPage uploadFile(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String slug,
                                 @RequestParam(required = false) String summary,
                                 HttpServletRequest http) {
        String html;
        try {
            html = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file", e);
        }
        String name = file.getOriginalFilename();
        String fallbackSlug = slug != null ? slug
                : name == null ? null : name.replaceFirst("\\.[^.]*$", "");
        return publish(html, fallbackSlug, title, summary, http);
    }

    /** Everything published so far, newest first. */
    @GetMapping
    public List<StoredPage> list() {
        return service.list();
    }

    /** One published page's metadata. */
    @GetMapping("/{slug}")
    public StoredPage get(@PathVariable String slug) {
        StoredPage page = service.read(slug);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such page: " + slug);
        }
        return page;
    }

    /**
     * Store the page, then make it visible: regenerate the docs index so the new
     * card shows up, and commit both to git so the next deploy's
     * {@code git checkout main} can't roll the publication back (the same trap
     * that resurrected deleted docs in task #463).
     */
    private StoredPage publish(String html, String slug, String title, String summary, HttpServletRequest http) {
        StoredPage page = service.store(html, slug, title, summary, clientOf(http));
        generator.regenerate();
        gitCommitter.commitPaths("docs: publish uploaded page '" + page.slug() + "'",
                service.pathsFor(page.slug()));
        return page;
    }

    /** The caller's address for rate limiting — the tunnel's forwarded IP when present. */
    private static String clientOf(HttpServletRequest http) {
        if (http == null) {
            return null;
        }
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
