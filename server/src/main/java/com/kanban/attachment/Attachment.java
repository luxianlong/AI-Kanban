package com.kanban.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One file (today: an image) a user attached to a task as input for the agent.
 *
 * <p>The bytes live on disk under the attachment store, not in the database —
 * the row only records where they went and what they were called, so the worker
 * can hand the agent a real path it can open, and the UI can render a preview.
 *
 * <p>An upload is created <em>before</em> the task exists (the composer uploads
 * as soon as a file is picked, so it can show a thumbnail), so {@link #todoId}
 * is null until the task is submitted and claims it. A row that is never claimed
 * is a harmless orphan — see {@link AttachmentService#deleteUnclaimedOlderThan}.
 */
@Entity
@Table(name = "attachments")
public class Attachment {

    /** Opaque id, also the on-disk file name (plus the original extension). */
    @Id
    private String id;

    /** The task this file belongs to, or null while it is still unclaimed. */
    private Long todoId;

    /** File name as the user's browser reported it, for display only. */
    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    /** File name under the store's directory (id + the original extension). */
    @Column(nullable = false)
    private String storedName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // --- getters / setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getTodoId() {
        return todoId;
    }

    public void setTodoId(Long todoId) {
        this.todoId = todoId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
