package com.kanban.attachment;

import com.kanban.attachment.AttachmentDtos.AttachmentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores task attachments (images) on disk and links them to a todo.
 *
 * <p>Why disk and not the database: the whole point of an attachment is that the
 * Claude session resolving the task can <em>open</em> it. The worker runs in the
 * repo's working directory on this same machine, so handing the agent an absolute
 * path to a real file is all it needs (see {@code ClaudeWorkerService}). Keeping
 * bytes out of H2 also keeps the live database small and the deploy snapshot fast.
 *
 * <p>Uploads happen before the task is created (the composer wants a thumbnail as
 * soon as a file is picked), so a fresh row is <em>unclaimed</em> ({@code todoId}
 * null) until {@link #attach} links it to the submitted task.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /**
     * Image types accepted. Deliberately a whitelist of raster formats: the files
     * are served back inline from the app's own origin, and SVG (which can carry
     * script) has no business being rendered there.
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif",
            "image/webp", "image/bmp", "image/heic", "image/heif");

    /** Fallback extension per type, used when the upload has no usable one. */
    private static final Map<String, String> TYPE_EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp",
            "image/bmp", "bmp",
            "image/heic", "heic",
            "image/heif", "heif");

    private final AttachmentRepository repository;
    private final Path directory;
    private final long maxBytes;

    public AttachmentService(
            AttachmentRepository repository,
            // Sits beside the live H2 database so one data directory holds all
            // user state (and the deploy's data/ snapshot covers it too).
            @Value("${attachments.dir:${KANBAN_DATA_DIR:${user.home}/Dashboard/data}/attachments}") String directory,
            @Value("${attachments.max-bytes:10485760}") long maxBytes) {
        this.repository = repository;
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /** Where uploaded files land. Exposed so callers can log/diagnose the store. */
    public Path directory() {
        return directory;
    }

    /**
     * Save an uploaded image and record it as unclaimed. Rejects anything that is
     * not an allowed image type or is over the size cap (400).
     */
    public Attachment store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded.");
        }
        String type = normalizeType(file.getContentType());
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type: " + (file.getContentType() == null ? "unknown" : file.getContentType())
                            + ". Only images (PNG, JPEG, GIF, WebP, BMP, HEIC) can be attached.");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File is too large (" + file.getSize() + " bytes); the limit is " + maxBytes + " bytes.");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String storedName = id + "." + extensionFor(file.getOriginalFilename(), type);
        try {
            Files.createDirectories(directory);
            // The stored name is built from a generated id plus a sanitised
            // extension, so a hostile upload name can never escape the store.
            Path target = directory.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not store attachment in {}", directory, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not store the uploaded file.");
        }

        Attachment a = new Attachment();
        a.setId(id);
        a.setFileName(displayName(file.getOriginalFilename(), storedName));
        a.setContentType(type);
        a.setSizeBytes(file.getSize());
        a.setStoredName(storedName);
        return repository.save(a);
    }

    /**
     * Claim the given uploads for a task. Unknown ids are ignored (a client may
     * send a stale id), and an upload already claimed by another task is left
     * alone — an attachment belongs to exactly one todo.
     */
    public List<Attachment> attach(List<String> ids, Long todoId) {
        if (ids == null || ids.isEmpty() || todoId == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> repository.findById(id.trim()).orElse(null))
                .filter(a -> a != null && (a.getTodoId() == null || a.getTodoId().equals(todoId)))
                .map(a -> {
                    a.setTodoId(todoId);
                    return repository.save(a);
                })
                .toList();
    }

    public List<Attachment> listFor(Long todoId) {
        return todoId == null ? List.of() : repository.findByTodoIdOrderByCreatedAtAsc(todoId);
    }

    public List<AttachmentView> viewsFor(Long todoId) {
        return listFor(todoId).stream().map(AttachmentView::from).toList();
    }

    /** Views for many todos at once, so listing the board stays a single query. */
    public Map<Long, List<AttachmentView>> viewsForAll(List<Long> todoIds) {
        Map<Long, List<AttachmentView>> byTodo = new HashMap<>();
        if (todoIds == null || todoIds.isEmpty()) {
            return byTodo;
        }
        for (Attachment a : repository.findByTodoIdInOrderByCreatedAtAsc(todoIds)) {
            byTodo.computeIfAbsent(a.getTodoId(), k -> new java.util.ArrayList<>())
                    .add(AttachmentView.from(a));
        }
        return byTodo;
    }

    /** Absolute on-disk path of an attachment — what the agent is told to open. */
    public Path pathOf(Attachment a) {
        return directory.resolve(a.getStoredName());
    }

    public Attachment require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found: " + id));
    }

    public byte[] read(Attachment a) {
        try {
            return Files.readAllBytes(pathOf(a));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment file is missing: " + a.getId());
        }
    }

    /** Remove one attachment (row + file). Used by the composer's "remove". */
    public void delete(Attachment a) {
        deleteFile(a);
        repository.delete(a);
    }

    /** Remove every attachment of a task — called when the task itself is deleted. */
    public void deleteForTodo(Long todoId) {
        List<Attachment> owned = listFor(todoId);
        owned.forEach(this::deleteFile);
        repository.deleteAll(owned);
    }

    /**
     * Drop uploads that were never submitted with a task and are older than the
     * given age, so an abandoned composer doesn't leak files forever.
     */
    public int deleteUnclaimedOlderThan(Duration age) {
        List<Attachment> stale = repository.findByTodoIdIsNullAndCreatedAtBefore(Instant.now().minus(age));
        stale.forEach(this::deleteFile);
        repository.deleteAll(stale);
        return stale.size();
    }

    private void deleteFile(Attachment a) {
        try {
            Files.deleteIfExists(pathOf(a));
        } catch (IOException e) {
            log.warn("Could not delete attachment file {}: {}", a.getStoredName(), e.getMessage());
        }
    }

    private String normalizeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        // Strip any parameters ("image/jpeg; charset=…") and normalise case.
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    /** Extension taken from the upload name when sane, else derived from the type. */
    private String extensionFor(String originalName, String type) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        return TYPE_EXTENSIONS.getOrDefault(type, "img");
    }

    /** Display name: the browser's file name, minus any path, or the stored one. */
    private String displayName(String originalName, String storedName) {
        if (originalName == null || originalName.isBlank()) {
            return storedName;
        }
        String base = Paths.get(originalName.replace('\\', '/')).getFileName().toString().trim();
        if (base.isEmpty()) {
            return storedName;
        }
        return base.length() > 200 ? base.substring(0, 200) : base;
    }
}
