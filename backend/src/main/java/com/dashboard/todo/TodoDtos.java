package com.dashboard.todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response payloads for the todo API. */
public final class TodoDtos {

    private TodoDtos() {
    }

    /** Body for creating a todo. */
    public record CreateTodoRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String description) {
    }

    /** Body for updating a todo's status (used by the worker and the UI). */
    public record UpdateStatusRequest(
            @NotBlank String status,
            String result) {
    }

    /** Full view returned to clients. */
    public record TodoResponse(
            Long id,
            String title,
            String description,
            TodoStatus status,
            String result,
            Instant createdAt,
            Instant updatedAt,
            Instant processedAt,
            Double costUsd,
            Long durationMs) {

        public static TodoResponse from(Todo t) {
            return new TodoResponse(
                    t.getId(),
                    t.getTitle(),
                    t.getDescription(),
                    t.getStatus(),
                    t.getResult(),
                    t.getCreatedAt(),
                    t.getUpdatedAt(),
                    t.getProcessedAt(),
                    t.getCostUsd(),
                    t.getDurationMs());
        }
    }
}
