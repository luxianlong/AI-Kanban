package com.kanban.docs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final DocsGitCommitter gitCommitter;
    private final DocReadRepository reads;
    private final DocViewRepository views;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DocsController(WorkerProperties props, DocsSiteGenerator generator,
                          DocsGitCommitter gitCommitter, DocReadRepository reads,
                          DocViewRepository views) {
        this.repoRoot = Path.of(props.getWorkingDirectory());
        this.generator = generator;
        this.gitCommitter = gitCommitter;
        this.reads = reads;
        this.views = views;
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

    /** Body for marking documents seen: the manifest ids the user just viewed. */
    public record MarkReadRequest(List<String> ids) {}

    /**
     * The ids of every document the user has already opened. The board diffs this
     * against the manifest to size the "new docs" badge — a doc whose id isn't
     * here is still unseen. Server-side so the badge is consistent across devices.
     */
    @GetMapping("/reads")
    public List<String> reads() {
        return reads.findAll().stream().map(DocRead::getDocId).toList();
    }

    /**
     * Mark documents as seen (called when the user opens the docs index), clearing
     * them from the "new" badge on every device. Idempotent: an id already recorded
     * keeps its original seen time; unknown/blank ids are ignored.
     */
    @PostMapping("/reads")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestBody MarkReadRequest req) {
        if (req == null || req.ids() == null) {
            return;
        }
        Instant now = Instant.now();
        for (String id : req.ids()) {
            if (id == null || id.isBlank() || reads.existsById(id)) {
                continue;
            }
            reads.save(new DocRead(id, now));
        }
    }

    /** Body for recording visits: the manifest ids that were just opened. */
    public record RecordViewRequest(List<String> ids) {}

    /**
     * How many times each document has been opened (task #523), keyed by manifest
     * id. Only documents that still exist in the manifest are reported, so a
     * tally left behind by a deleted doc never resurfaces. Docs never visited are
     * simply absent — the reader treats a missing key as zero.
     */
    @GetMapping("/views")
    public Map<String, Long> views() {
        Set<String> known = readManifest().stream().map(DocEntry::id).collect(Collectors.toSet());
        Map<String, Long> out = new LinkedHashMap<>();
        for (DocView v : views.findAll()) {
            if (known.contains(v.getDocId())) {
                out.put(v.getDocId(), v.getViewCount());
            }
        }
        return out;
    }

    /**
     * Record one visit for each given document. Called by the Vite gate when it
     * serves a doc page — server-side, so a visit counts even for pages with no
     * script of their own and for readers who block JavaScript. Ids the manifest
     * doesn't know are ignored, so nobody can seed the table with junk rows.
     */
    @PostMapping("/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void recordViews(@RequestBody RecordViewRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return;
        }
        Set<String> known = readManifest().stream().map(DocEntry::id).collect(Collectors.toSet());
        Instant now = Instant.now();
        for (String id : req.ids()) {
            if (id == null || id.isBlank() || !known.contains(id)) {
                continue;
            }
            if (views.bump(id, now) == 0) {
                views.save(new DocView(id, 1, now));
            }
        }
    }

    /**
     * Where the per-doc privacy state lives: a gitignored sidecar
     * {@code {"private":[...ids]}} written here and read by the docs generator
     * (to stamp the manifest/index) and the Vite auth gate (to enforce it).
     */
    private static final String VISIBILITY_REL = "frontend/.doc-visibility.json";

    /** Body for {@link #setVisibility}: whether to mark the document private. */
    public record VisibilityRequest(@JsonProperty("private") Boolean isPrivate) {}

    /**
     * Mark a document public or private (task #268). Documents are public by
     * default — anyone can read them without the site password — but the owner can
     * flip one private so only they (behind the gate) can open it. Persists the
     * choice to the visibility sidecar and regenerates the site so the manifest,
     * the index badges and the auth gate all reflect it immediately.
     */
    @PutMapping("/{id}/visibility")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setVisibility(@PathVariable String id, @RequestBody VisibilityRequest req) {
        boolean known = readManifest().stream().anyMatch(e -> id.equals(e.id()));
        if (!known) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document: " + id);
        }
        boolean makePrivate = req != null && Boolean.TRUE.equals(req.isPrivate());
        writePrivate(id, makePrivate);
        log.info("Set document '{}' visibility to {}", id, makePrivate ? "private" : "public");
        generator.regenerate();
    }

    /** The ids currently marked private, in stable insertion order. */
    private LinkedHashSet<String> readPrivate() {
        Path file = repoRoot.resolve(VISIBILITY_REL);
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            Map<String, List<String>> parsed =
                    mapper.readValue(Files.readAllBytes(file), new TypeReference<Map<String, List<String>>>() {});
            List<String> ids = parsed == null ? null : parsed.get("private");
            return ids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ids);
        } catch (IOException e) {
            log.warn("Could not read visibility file — treating nothing as private", e);
            return new LinkedHashSet<>();
        }
    }

    /** Add or remove {@code id} from the private set and persist it. */
    private void writePrivate(String id, boolean makePrivate) {
        LinkedHashSet<String> ids = readPrivate();
        if (makePrivate) {
            ids.add(id);
        } else {
            ids.remove(id);
        }
        Path file = repoRoot.resolve(VISIBILITY_REL);
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("private", List.copyOf(ids));
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save document visibility: " + e.getMessage(), e);
        }
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
        // Persist the removal to git so the next deploy's `git checkout main`/merge
        // can't restore the still-committed files and resurrect the doc (task #463).
        gitCommitter.commitDeletion(id, paths);
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
