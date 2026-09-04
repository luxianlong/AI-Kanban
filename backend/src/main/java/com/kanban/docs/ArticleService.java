package com.kanban.docs;

import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes an article composed in the browser to {@code docs/<slug>.md} (task
 * #223 — "write article on the /docs.html page").
 *
 * <p>The docs site is generated from Markdown: every {@code docs/*.md} becomes a
 * page at {@code /docs/<slug>.html} in the archive's own typography and a card
 * on {@code /docs.html} (see {@code scripts/build-docs-site.mjs}). So "publish
 * what the user pasted into the pop-up" is exactly "drop one Markdown file in
 * {@code docs/} and re-run the generator" — the new article then looks, sorts,
 * counts views and deletes like every other document, with no second rendering
 * path to keep in sync.
 *
 * <p>This service only writes the source file; {@link DocsController#create}
 * runs the generator and commits both to git (a web-UI change to a git-tracked
 * file that isn't committed is rolled back by the next deploy's
 * {@code git checkout main} — see {@link DocsGitCommitter}).
 */
@Service
@EnableConfigurationProperties(WorkerProperties.class)
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    /** Where article sources live, relative to the repo root. */
    public static final String DOCS_REL = "docs";

    /** Generated pages land here — the path a delete has to remove alongside the source. */
    public static final String PAGES_REL = "frontend/public/docs";

    /**
     * Cap on one article's Markdown. Generous for prose (roughly a 200k-word
     * document) while still refusing a paste that would be a mistake or an abuse.
     */
    static final int MAX_BYTES = 512 * 1024;

    /** The document's own title line: the first ATX H1 in the Markdown. */
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private final Path repoRoot;

    public ArticleService(WorkerProperties props) {
        this.repoRoot = Path.of(props.getWorkingDirectory());
    }

    /** What the API hands back once the article is on disk. */
    public record NewArticle(String id, String title, String href, String file, long bytes,
                             String createdAt) {}

    /**
     * Save {@code markdown} as a new article. {@code title} and {@code slug} are
     * optional: the title falls back to the Markdown's own H1 and the slug to the
     * title, then to a random name for titles that carry no URL-safe characters
     * (e.g. an all-Chinese one). An existing slug is never overwritten — a
     * collision gets a {@code -2}, {@code -3}… suffix.
     */
    public NewArticle write(String markdown, String title, String slug) {
        if (markdown == null || markdown.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The article is empty");
        }
        String body = markdown.replace("\r\n", "\n").strip();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The article is larger than the " + MAX_BYTES + " byte limit");
        }

        String heading = extractH1(body);
        String finalTitle = firstNonBlank(clean(title), heading);
        // The generator reads the page title from the document's H1, so an article
        // titled only in the form field would publish under its (ASCII) filename.
        // Give it the heading it lacks rather than carrying the title elsewhere.
        if (heading == null && finalTitle != null) {
            body = "# " + finalTitle + "\n\n" + body;
        }

        String base = firstNonBlank(slugify(slug), slugify(finalTitle));
        if (base == null) {
            // Chinese titles slugify to nothing, and a CJK filename would have to be
            // percent-encoded to be served — keep the URL ASCII and let the H1 carry
            // the real title.
            base = "article-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Path docsDir = repoRoot.resolve(DOCS_REL);
        try {
            Files.createDirectories(docsDir);
            String name = base;
            for (int n = 2; Files.exists(docsDir.resolve(name + ".md")); n++) {
                name = base + "-" + n;
            }
            if (finalTitle == null) {
                finalTitle = humanize(name);
                body = "# " + finalTitle + "\n\n" + body;
            }
            byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(docsDir.resolve(name + ".md"), bytes);
            log.info("Wrote article '{}' ({} bytes) from the docs page", name, bytes.length);
            return new NewArticle(name, finalTitle, "/docs/" + name + ".html",
                    DOCS_REL + "/" + name + ".md", bytes.length, Instant.now().toString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save the article: " + e.getMessage(), e);
        }
    }

    /** The repo-relative files backing a just-written article — source + generated page. */
    public List<String> pathsFor(String id) {
        return List.of(DOCS_REL + "/" + id + ".md", PAGES_REL + "/" + id + ".html");
    }

    // --- internals -----------------------------------------------------------

    /** The Markdown's own title line, or null when it opens with something else. */
    private static String extractH1(String markdown) {
        Matcher m = H1.matcher(markdown);
        return m.find() ? clean(m.group(1)) : null;
    }

    /** Collapse whitespace and cap the length; null when nothing is left. */
    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() > 200) {
            t = t.substring(0, 200).trim();
        }
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    /**
     * A filename-safe slug: lowercase ASCII letters/digits joined by single
     * dashes, null when nothing survives. This is the only path sanitiser the
     * write API has — a slug can hold neither a separator nor a dot, so it can
     * never escape {@code docs/}.
     */
    static String slugify(String s) {
        if (s == null) {
            return null;
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (t.length() > 64) {
            t = t.substring(0, 64).replaceAll("-+$", "");
        }
        return t.isEmpty() ? null : t;
    }

    /** "my-article" -> "My article", for an article that arrived with no title at all. */
    private static String humanize(String slug) {
        String s = slug.replace('-', ' ').trim();
        return s.isEmpty() ? slug : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
