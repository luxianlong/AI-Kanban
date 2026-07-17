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
import java.util.List;

/**
 * REST API backing the "Delete" button on the docs index page (/docs.html).
 *
 * <p>The docs site is a static build: every {@code docs/*.md} is rendered to a
 * page and listed in {@code frontend/public/docs-manifest.json}. Deleting a
 * document therefore means removing its source Markdown plus its generated page,
 * then regenerating the index/manifest so the card disappears. Only "plain"
 * single-file docs are deletable (the manifest flags them); bespoke pages and
 * multi-file stitched reports are not, since removing them cleanly isn't a
 * single-file operation.
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

    /** One entry in docs-manifest.json. Extra fields (if any) are ignored. */
    public record DocEntry(String id, String title, String href, boolean deletable, String file) {}

    /** The docs the board knows about — mirrors docs-manifest.json. */
    @GetMapping
    public List<DocEntry> list() {
        return readManifest();
    }

    /**
     * Delete a document by its manifest id: remove the source Markdown and the
     * generated page, then regenerate the index + manifest. Idempotent — a
     * second call 404s once the entry is gone.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        DocEntry entry = readManifest().stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document: " + id));

        if (!entry.deletable() || entry.file() == null || entry.file().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This document can't be deleted from the web UI: " + id);
        }

        Path docsDir = repoRoot.resolve("docs").normalize();
        Path source = repoRoot.resolve(entry.file()).normalize();
        // Path-traversal guard: only ever delete a file sitting directly under docs/.
        if (!source.startsWith(docsDir) || !docsDir.equals(source.getParent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Refusing to delete a file outside docs/: " + entry.file());
        }

        try {
            Files.deleteIfExists(source);
            // Drop the generated page too so its direct URL 404s right away; the
            // generator only writes pages for surviving docs, it won't prune this.
            Path generated = repoRoot.resolve("frontend").resolve("public").resolve("docs")
                    .resolve(id + ".html").normalize();
            Files.deleteIfExists(generated);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete document files: " + e.getMessage(), e);
        }

        log.info("Deleted document '{}' ({})", id, entry.file());
        // Refresh index + manifest from the surviving docs (best-effort).
        generator.regenerate();
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
