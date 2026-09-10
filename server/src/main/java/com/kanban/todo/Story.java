package com.kanban.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A persistent <em>story</em>: the container for an ordered chain of tasks. It is
 * kept as its own row (keyed by the shared {@code storyId} the steps carry) so a
 * story keeps existing on the board even when it has <em>no</em> tasks — e.g. after
 * every step has been done and cleared away. The steps themselves live on
 * {@link Todo} (tagged with this id); this record just pins the story's identity
 * and name so an empty chain doesn't vanish. A story only disappears when the user
 * deletes it explicitly (see {@code StoryController.delete}).
 */
@Entity
@Table(name = "stories")
public class Story {

    /** The story's id — the same value every step carries as its {@code storyId}. */
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Story() {
    }

    public Story(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
