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

import org.hibernate.annotations.ColumnDefault;

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

    /**
     * Whether this task is about improving the Kanban project itself (its code,
     * docs, tests) rather than a general task. Defaults to {@code false}: a plain
     * task is not aimed at the project unless the creator opts in.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean projectImprovement = false;

    /**
     * Whether this is a <em>delegated</em> ("代理") task: a task this server
     * deliberately never runs. The worker skips it entirely; instead it is
     * published through the unauthenticated {@code /api/public/tasks} API so an
     * outside agent (another bot, a colleague, a script) can find it, do the
     * work wherever it lives, and report the outcome back through the same
     * public API. Defaults to {@code false} — a normal task is still resolved by
     * this server's own Claude sessions.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean delegated = false;

    /**
     * Name the outside worker gave when it claimed this delegated task, or
     * {@code null} while nobody has claimed it. Purely informational — claiming
     * is optional, and the name is self-reported (the endpoint is anonymous).
     */
    private String delegatedTo;

    /** When an outside worker claimed this delegated task, if it did. */
    private Instant delegateClaimedAt;

    /**
     * Secret minted when a delegated task is claimed and returned only to the
     * claimer. Once set, reporting the outcome requires it, so a second passer-by
     * can't overwrite the status of work somebody else is already doing. Null
     * while unclaimed, in which case anyone may report the outcome directly.
     */
    private String delegateToken;

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
     * A story's planner ({@link #storyPlanner}) has a {@code null} order — it is
     * not a numbered step but the seed the worker expands into steps.
     */
    private Integer storyOrder;

    /**
     * Whether this item is a story's <em>planner</em>: the single seed task
     * created when a user declares a story from a one-line goal. When the worker
     * picks it up it does not do the work itself — it decomposes the goal into
     * concrete step tasks filed under the same {@link #storyId}. Regular tasks
     * (including the generated steps) are {@code false}.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean storyPlanner = false;

    /**
     * Transient hold used only while the smart (async Claude) story classifier is
     * deciding which story a freshly-created standalone task belongs to. The worker
     * skips a {@code classifying} task so it can't start running before the pass
     * finishes filing it into a story. The classifier clears this the moment it
     * resolves (into a story, or released to run standalone); a restart clears any
     * left set, since the async pass doesn't survive the process.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean classifying = false;

    /**
     * Whether this item is an <em>unread</em> finished-task notification. A task is
     * flagged {@code true} the moment it crosses into a terminal state
     * ({@link TodoStatus#RESOLVED}/{@link TodoStatus#FAILED}) and cleared once the
     * user opens it (or hits "mark all read"). Making it a persisted property — not
     * a per-browser localStorage flag — is what lets the notification badge stay in
     * sync when the same user opens the board from several devices. Defaults to
     * {@code false}, so tasks that were already finished when this column was added
     * don't retroactively light up the badge.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean unread = false;

    /**
     * How many times the worker has <em>started</em> processing this item (each
     * pickup bumps it in {@link TodoStatus#IN_PROGRESS}). Used to cap automatic
     * re-queuing of tasks orphaned by a restart: a backend restart is often
     * triggered by a <em>sibling</em> improvement task's guarded deploy, which
     * kills whatever task is running at the time — even an unrelated one that was
     * legitimately in-flight and may have already produced side effects. Blindly
     * returning it to the queue re-executes work that was already (partly) done,
     * so past a small cap the task is routed to Needs Attention instead. Defaults
     * to {@code 0} so rows predating this column don't look already-exhausted.
     */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int attempts = 0;

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

    public boolean isProjectImprovement() {
        return projectImprovement;
    }

    public void setProjectImprovement(boolean projectImprovement) {
        this.projectImprovement = projectImprovement;
    }

    public boolean isClassifying() {
        return classifying;
    }

    public void setClassifying(boolean classifying) {
        this.classifying = classifying;
    }

    public boolean isUnread() {
        return unread;
    }

    public void setUnread(boolean unread) {
        this.unread = unread;
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

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
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

    public boolean isDelegated() {
        return delegated;
    }

    public void setDelegated(boolean delegated) {
        this.delegated = delegated;
    }

    public String getDelegatedTo() {
        return delegatedTo;
    }

    public void setDelegatedTo(String delegatedTo) {
        this.delegatedTo = delegatedTo;
    }

    public Instant getDelegateClaimedAt() {
        return delegateClaimedAt;
    }

    public void setDelegateClaimedAt(Instant delegateClaimedAt) {
        this.delegateClaimedAt = delegateClaimedAt;
    }

    public String getDelegateToken() {
        return delegateToken;
    }

    public void setDelegateToken(String delegateToken) {
        this.delegateToken = delegateToken;
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

    public boolean isStoryPlanner() {
        return storyPlanner;
    }

    public void setStoryPlanner(boolean storyPlanner) {
        this.storyPlanner = storyPlanner;
    }
}
