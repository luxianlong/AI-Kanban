package com.kanban.todo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response payloads for the todo API. */
public final class TodoDtos {

    private TodoDtos() {
    }

    /**
     * Body for creating a todo. {@code projectImprovement} marks the task as one
     * that improves the Kanban project itself; it is optional and defaults to
     * {@code false} (a general task) when omitted.
     */
    public record CreateTodoRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String description,
            Boolean projectImprovement) {
    }

    /** One step of a story chain. */
    public record StoryStep(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String description) {
    }

    /**
     * Body for creating a story from a single one-line goal. No steps are given
     * up front: the server files one planner task, and the worker decomposes the
     * goal into concrete step tasks when it processes it. {@code description} is
     * optional extra context for that planning.
     */
    public record CreateStoryRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 10_000) String description) {
    }

    /** Body for asking the server to decompose a free-text goal into steps. */
    public record SuggestStoryRequest(
            @NotBlank @Size(max = 10_000) String goal) {
    }

    /** A proposed story: a suggested name plus the generated ordered steps. */
    public record SuggestStoryResponse(
            String name,
            List<StoryStep> steps) {
    }

    /**
     * An existing story that a piece of text looks related to, with a relevance
     * score (shared meaningful words). Used to suggest attaching a task to a story.
     */
    public record RelatedStory(
            String storyId,
            String name,
            int score) {
    }

    /**
     * A story and its ordered steps. Returned when a story is created or listed,
     * so a client can render the whole chain and its progress in one shot.
     */
    public record StoryResponse(
            String storyId,
            String name,
            Instant createdAt,
            List<TodoResponse> steps) {

        /** Build from the steps of one story, ordered by {@code storyOrder}. */
        public static StoryResponse from(String storyId, String name, List<Todo> steps) {
            return from(storyId, name, null, steps);
        }

        /**
         * Build from a story's steps, falling back to {@code createdAt} when the
         * chain is empty — an empty (task-less) story still reports the moment it
         * was declared, so it keeps a stable place in newest-first ordering.
         */
        public static StoryResponse from(String storyId, String name, Instant createdAt, List<Todo> steps) {
            Instant when = steps.isEmpty() ? createdAt : steps.get(0).getCreatedAt();
            return new StoryResponse(
                    storyId,
                    name,
                    when,
                    steps.stream().map(TodoResponse::from).toList());
        }
    }

    /** Body for updating a todo's status (used by the worker and the UI). */
    public record UpdateStatusRequest(
            @NotBlank String status,
            String result) {
    }

    /**
     * Manually file a task under a story, or unassign it. A null/blank
     * {@code storyId} makes the task standalone; otherwise it must be an existing
     * story's id and the task is appended to the end of that chain.
     */
    public record SetStoryRequest(String storyId) {
    }

    /** Full view returned to clients. */
    public record TodoResponse(
            Long id,
            String title,
            String description,
            TodoStatus status,
            String result,
            boolean projectImprovement,
            Instant createdAt,
            Instant updatedAt,
            Instant processedAt,
            Double costUsd,
            Long durationMs,
            String storyId,
            String storyName,
            Integer storyOrder,
            boolean storyPlanner) {

        public static TodoResponse from(Todo t) {
            return new TodoResponse(
                    t.getId(),
                    t.getTitle(),
                    t.getDescription(),
                    t.getStatus(),
                    t.getResult(),
                    t.isProjectImprovement(),
                    t.getCreatedAt(),
                    t.getUpdatedAt(),
                    t.getProcessedAt(),
                    t.getCostUsd(),
                    t.getDurationMs(),
                    t.getStoryId(),
                    t.getStoryName(),
                    t.getStoryOrder(),
                    t.isStoryPlanner());
        }
    }
}
