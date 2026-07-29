package com.kanban.attachment;

import com.kanban.attachment.AttachmentDtos.AttachmentView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Upload / fetch / remove task attachments (images).
 *
 * <p>The composer uploads a file the moment it is picked — before the task
 * exists — so it can show a thumbnail immediately; the returned id is then sent
 * with {@code POST /api/todos} as {@code attachmentIds} to bind the image to the
 * new task. The image is stored server-side, so the agent that resolves the task
 * can open the real file.
 */
@RestController
@RequestMapping("/api/attachments")
@CrossOrigin(origins = "*")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    /** Upload one image. Returns the id the task-create call should reference. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView upload(@RequestParam("file") MultipartFile file) {
        // Opportunistic housekeeping: uploads that were picked but never
        // submitted (composer abandoned) are dropped after a day.
        service.deleteUnclaimedOlderThan(Duration.ofDays(1));
        return AttachmentView.from(service.store(file));
    }

    /** Serve the stored bytes, inline, so the UI can render a preview. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        Attachment a = service.require(id);
        byte[] bytes = service.read(a);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                // Never render an attachment as a document in its own right.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    /** Remove an attachment (e.g. the user un-picked it in the composer). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(service.require(id));
    }
}
