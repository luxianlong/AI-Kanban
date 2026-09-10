package com.kanban.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One step kept aside to be used again: a name, a prompt, and the effort level it
 * wants — the three things a {@link WorkflowNode} is, minus the graph it sat in.
 *
 * <p>A favourite is a <em>copy</em>, not a reference. Inserting one writes a new node
 * carrying today's text, and the two go their separate ways from that moment: editing
 * the step afterwards does not touch the favourite, and re-saving the favourite does
 * not reach back into workflows already built from it. That is deliberate — the
 * alternative is a stored prompt that silently rewrites finished graphs, and a run
 * whose steps nobody can account for.
 *
 * <p>Position and connections are not part of one: where a step sits and what feeds it
 * belong to the graph, not to the work the step does, and are the two things that are
 * certainly different in the workflow it is being inserted into.
 *
 * <p>The name is the identity users work with, so it is unique (case-insensitively —
 * see {@code FavoriteStepService}); saving over a name replaces that favourite rather
 * than growing a second one beside it.
 */
@Entity
@Table(name = "workflow_favorite_steps")
public class FavoriteStep {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** Null means the inserted step runs at the CLI's default — see {@link EffortLevel}. */
    @Enumerated(EnumType.STRING)
    private EffortLevel effort;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FavoriteStep() {
    }

    public FavoriteStep(String id, String name, String prompt, EffortLevel effort) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.effort = effort;
    }

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

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public EffortLevel getEffort() {
        return effort;
    }

    public void setEffort(EffortLevel effort) {
        this.effort = effort;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
