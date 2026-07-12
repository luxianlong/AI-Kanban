package com.kanban.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single unit of work created by a user. A scheduled Claude Code session
 * picks up items in {@link TodoStatus#PENDING} and tries to resolve them,
 * writing whatever the session produced back into {@link #result}.
 */
@Entity
@Table(name = "todos")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TodoStatus status = TodoStatus.PENDING;

    /** Free-form output the Claude Code session produced for this item. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String result;

    /**
     * Identifier of the story (an ordered chain of tasks) this item belongs to,
     * or {@code null} for a standalone task. All steps of one story share this id.
     */
    private String storyId;

    /** Human-readable name of the story, duplicated on each step for display. */
    private String storyName;

    /**
     * Zero-based position of this task within its story chain. The worker only
     * picks up a step once every earlier step in the same story is
     * {@link TodoStatus#RESOLVED}, so a story runs one task at a time in order.
     */
    private Integer storyOrder;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** When the worker last started/finished processing this item. */
    private Instant processedAt;

    /** Total USD cost the Claude Code session reported, if any. */
    private Double costUsd;

    /** Wall-clock duration of the Claude Code session in milliseconds, if any. */
    private Long durationMs;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- getters / setters ---

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public void setStatus(TodoStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(Double costUsd) {
        this.costUsd = costUsd;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStoryId() {
        return storyId;
    }

    public void setStoryId(String storyId) {
        this.storyId = storyId;
    }

    public String getStoryName() {
        return storyName;
    }

    public void setStoryName(String storyName) {
        this.storyName = storyName;
    }

    public Integer getStoryOrder() {
        return storyOrder;
    }

    public void setStoryOrder(Integer storyOrder) {
        this.storyOrder = storyOrder;
    }
}
