package com.kanban.pages;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores static web pages published through the unauthenticated upload API
 * (task #470 — "创建一个 api 不需校验就可以上传静态网页到这个看板").
 *
 * <p>A page is one self-contained HTML file. It is written to
 * {@code frontend/public/p/<slug>.html}, which Vite serves straight from the web
 * root, so it is readable at {@code /p/<slug>.html} by anyone — the docs gate in
 * {@code frontend/docsGate.mjs} classifies that path as a public document, so no
 * site password is asked for. A {@code <slug>.meta.json} sidecar records the
 * title/summary so the docs-site generator can list the page as a card on
 * {@code /docs.html} alongside the board's own reports.
 *
 * <p>Because callers are anonymous, the service never overwrites an existing
 * page (a colliding slug gets a {@code -2}, {@code -3}… suffix) and enforces the
 * size/count/rate caps in {@link PagesProperties}.
 */
@Service
@EnableConfigurationProperties({PagesProperties.class, WorkerProperties.class})
public class PageUploadService {

    private static final Logger log = LoggerFactory.getLogger(PageUploadService.class);

    /** Where uploaded pages live, relative to the repo root (served at /p/). */
    public static final String PAGES_REL = "frontend/public/p";

    /** Prefix the docs manifest uses for an uploaded page's id (see build-docs-site.mjs). */
    public static final String DOC_ID_PREFIX = "upload-";

    private static final Pattern TITLE_TAG =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_TAG =
            Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private final Path pagesDir;
    private final PagesProperties props;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Per-client upload timestamps, for the rolling hourly rate limit. */
    private final Map<String, Deque<Instant>> recentUploads = new ConcurrentHashMap<>();

    public PageUploadService(WorkerProperties worker, PagesProperties props) {
        this.pagesDir = Path.of(worker.getWorkingDirectory()).resolve(PAGES_REL);
        this.props = props;
    }

    /** What the API hands back (and what the sidecar stores) for one page. */
    public record StoredPage(String slug, String docId, String title, String summary, String url,
                             long bytes, String createdAt) {}

    /**
     * Publish {@code html} as a new page. {@code desiredSlug}/{@code title}/
     * {@code summary} are all optional — the title falls back to the document's
     * own {@code <title>}/{@code <h1>}, and the slug to the title, then to a
     * random one for titles that carry no URL-safe characters (e.g. Chinese).
     */
    public StoredPage store(String html, String desiredSlug, String title, String summary, String client) {
        if (html == null || html.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No HTML content in the request");
        }
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > props.getMaxBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Page is larger than the " + props.getMaxBytes() + " byte limit");
        }
        checkRateLimit(client);

        String finalTitle = firstNonBlank(clean(title), extract(TITLE_TAG, html), extract(H1_TAG, html));
        String base = firstNonBlank(slugify(desiredSlug), slugify(finalTitle));
        if (base == null) {
            base = "page-" + UUID.randomUUID().toString().substring(0, 8);
        }

        try {
            Files.createDirectories(pagesDir);
            if (countPages() >= props.getMaxPages()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The board already holds the maximum of " + props.getMaxPages() + " uploaded pages");
            }
            // Never clobber an existing page: an anonymous caller must not be able
            // to replace someone else's publication by guessing its slug.
            String slug = base;
            for (int n = 2; Files.exists(pagesDir.resolve(slug + ".html")); n++) {
                slug = base + "-" + n;
            }
            if (finalTitle == null) {
                finalTitle = humanize(slug);
            }
            String createdAt = Instant.now().toString();
            StoredPage page = new StoredPage(slug, DOC_ID_PREFIX + slug, finalTitle, clean(summary),
                    "/p/" + slug + ".html", bytes.length, createdAt);
            Files.write(pagesDir.resolve(slug + ".html"), bytes);
            writeMeta(page);
            recordUpload(client);
            log.info("Published uploaded page '{}' ({} bytes) from {}", slug, bytes.length, client);
            return page;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save the uploaded page: " + e.getMessage(), e);
        }
    }

    /** Every published page, newest first. */
    public List<StoredPage> list() {
        if (!Files.isDirectory(pagesDir)) {
            return List.of();
        }
        List<StoredPage> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(pagesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".html"))
                    .forEach(p -> {
                        StoredPage page = read(p.getFileName().toString().replaceFirst("\\.html$", ""));
                        if (page != null) {
                            out.add(page);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list uploaded pages", e);
        }
        out.sort(Comparator.comparing(StoredPage::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** One page's metadata, or null when no such page is published. */
    public StoredPage read(String slug) {
        String safe = slugify(slug);
        if (safe == null) {
            return null;
        }
        Path html = pagesDir.resolve(safe + ".html");
        if (!Files.isRegularFile(html)) {
            return null;
        }
        Map<String, Object> meta = readMeta(safe);
        long bytes;
        String createdAt;
        try {
            bytes = Files.size(html);
            createdAt = String.valueOf(meta.getOrDefault("createdAt",
                    Files.getLastModifiedTime(html).toInstant().toString()));
        } catch (IOException e) {
            return null;
        }
        String title = meta.get("title") instanceof String t && !t.isBlank() ? t : humanize(safe);
        String summary = meta.get("summary") instanceof String s && !s.isBlank() ? s : null;
        return new StoredPage(safe, DOC_ID_PREFIX + safe, title, summary, "/p/" + safe + ".html", bytes, createdAt);
    }

    /** The repo-relative files backing a page — what a delete has to remove. */
    public List<String> pathsFor(String slug) {
        return List.of(PAGES_REL + "/" + slug + ".html", PAGES_REL + "/" + slug + ".meta.json");
    }

    // --- internals -----------------------------------------------------------

    private long countPages() throws IOException {
        try (Stream<Path> files = Files.list(pagesDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".html")).count();
        }
    }

    private void writeMeta(StoredPage page) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("slug", page.slug());
        meta.put("title", page.title());
        meta.put("summary", page.summary());
        meta.put("createdAt", page.createdAt());
        meta.put("bytes", page.bytes());
        Files.write(pagesDir.resolve(page.slug() + ".meta.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
    }

    private Map<String, Object> readMeta(String slug) {
        Path file = pagesDir.resolve(slug + ".meta.json");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed =
                    mapper.readValue(Files.readAllBytes(file), new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            log.warn("Unreadable metadata for uploaded page '{}'", slug, e);
            return Map.of();
        }
    }

    /** Refuse a client that has already used up its hourly quota. */
    private void checkRateLimit(String client) {
        if (props.getMaxUploadsPerHour() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Page uploads are disabled");
        }
        Deque<Instant> hits = recentUploads.get(key(client));
        if (hits == null) {
            return;
        }
        synchronized (hits) {
            prune(hits);
            if (hits.size() >= props.getMaxUploadsPerHour()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many uploads from this client — try again later");
            }
        }
    }

    private void recordUpload(String client) {
        Deque<Instant> hits = recentUploads.computeIfAbsent(key(client), k -> new ArrayDeque<>());
        synchronized (hits) {
            prune(hits);
            hits.addLast(Instant.now());
        }
    }

    private static void prune(Deque<Instant> hits) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
            hits.removeFirst();
        }
    }

    private static String key(String client) {
        return client == null || client.isBlank() ? "unknown" : client;
    }

    /** Text of the first match of {@code pattern}, tags stripped; null when absent/blank. */
    private static String extract(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? clean(TAG.matcher(m.group(1)).replaceAll(" ")) : null;
    }

    /** Collapse whitespace, unescape the handful of entities a title may carry, cap the length. */
    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
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
     * dashes. Returns null when nothing survives (e.g. an all-Chinese title), so
     * callers can fall back to a random name. This is also the only path
     * sanitiser the upload API has — a slug can never contain a separator or a
     * dot, so it cannot escape the pages directory.
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

    /** "my-page" -> "My page", for a page that arrived with no usable title. */
    private static String humanize(String slug) {
        String s = slug.replace('-', ' ').trim();
        return s.isEmpty() ? slug : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
