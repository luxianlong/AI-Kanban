package com.kanban.docs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * REST API backing the "Delete" button on the docs index page (/docs.html).
 *
 * <p>The docs site is a static build: every {@code docs/*.md} is rendered to a
 * page and listed in {@code frontend/public/docs-manifest.json}. Deleting a
 * document therefore means removing every file/dir that backs it plus its
 * generated page, then regenerating the index/manifest so the card disappears.
 * The manifest records that full set per doc in {@code paths}, so all doc kinds
 * are deletable: plain single-file docs (source .md + generated page), bespoke
 * pages (source .md + hand-tuned HTML at the web root + any asset dir) and
 * multi-file stitched reports (the whole {@code research/<dir>/} + generated
 * page). Deletion is guarded to a small allow-list of roots so it can never
 * touch anything outside {@code docs/}, {@code research/} or
 * {@code frontend/public/}.
 */
@RestController
@RequestMapping("/api/docs")
@CrossOrigin(origins = "*")
@EnableConfigurationProperties(WorkerProperties.class)
public class DocsController {

    private static final Logger log = LoggerFactory.getLogger(DocsController.class);

    private final Path repoRoot;
    private final DocsSiteGenerator generator;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DocsController(WorkerProperties props, DocsSiteGenerator generator) {
        this.repoRoot = Path.of(props.getWorkingDirectory());
        this.generator = generator;
    }

    /**
     * Roots under the repo that a delete is ever allowed to touch. Every path in
     * a manifest entry's {@code paths} must resolve to somewhere strictly inside
     * one of these — never the root itself — so a delete can't escape docs land.
     */
    private static final List<String> ALLOWED_ROOTS = List.of("docs", "research", "frontend/public");

    /** One entry in docs-manifest.json. Extra fields (if any) are ignored. */
    public record DocEntry(String id, String title, String href, boolean deletable, String file,
                           List<String> paths) {}

    /** The docs the board knows about — mirrors docs-manifest.json. */
    @GetMapping
    public List<DocEntry> list() {
        return readManifest();
    }

    /**
     * Delete a document by its manifest id: remove every file/dir that backs it
     * (source Markdown or research dir, plus the generated page and any bespoke
     * assets), then regenerate the index + manifest. Idempotent — missing files
     * are skipped, and a second call 404s once the entry is gone.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        DocEntry entry = readManifest().stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document: " + id));

        if (!entry.deletable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This document can't be deleted from the web UI: " + id);
        }
        List<String> paths = entry.paths();
        if (paths == null || paths.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This document has no deletable files recorded: " + id);
        }

        // Validate every path up front (fail closed): if any escapes the allowed
        // roots, refuse the whole delete before touching the filesystem.
        for (String rel : paths) {
            Path target = repoRoot.resolve(rel).normalize();
            if (!isWithinAllowedRoot(target)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Refusing to delete a path outside the docs roots: " + rel);
            }
        }

        try {
            for (String rel : paths) {
                deleteRecursively(repoRoot.resolve(rel).normalize());
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete document files: " + e.getMessage(), e);
        }

        log.info("Deleted document '{}' ({} path(s))", id, paths.size());
        // Refresh index + manifest from the surviving docs (best-effort).
        generator.regenerate();
    }

    /**
     * True only when {@code target} sits strictly inside one of {@link #ALLOWED_ROOTS}
     * — never a root itself — so a delete can never wipe docs/, research/ or
     * frontend/public/ wholesale, nor reach anything above them.
     */
    private boolean isWithinAllowedRoot(Path target) {
        for (String rel : ALLOWED_ROOTS) {
            Path root = repoRoot.resolve(rel).normalize();
            if (target.startsWith(root) && !target.equals(root)) {
                return true;
            }
        }
        return false;
    }

    /** Delete a file, or a directory and everything under it. Missing paths are a no-op. */
    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    private List<DocEntry> readManifest() {
        Path manifest = repoRoot.resolve("frontend").resolve("public").resolve("docs-manifest.json");
        if (!Files.exists(manifest)) {
            return List.of();
        }
        try {
            return mapper.readValue(Files.readAllBytes(manifest), new TypeReference<List<DocEntry>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read docs manifest", e);
        }
    }
}
