package com.kanban.delegate;

import com.kanban.todo.Todo;
import com.kanban.todo.TodoStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response payloads for the public delegated-task API. */
public final class DelegatedTaskDtos {

    private DelegatedTaskDtos() {
    }

    /**
     * What an outside worker gets to see. Deliberately narrower than the board's
     * own {@code TodoResponse}: no cost/duration telemetry, no story bookkeeping,
     * no attachment urls (those live behind the site password) — just the work,
     * its state, and who took it.
     */
    public record DelegatedTaskView(
            Long id,
            String title,
            String description,
            TodoStatus status,
            String result,
            String claimedBy,
            Instant claimedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static DelegatedTaskView from(Todo t) {
            return new DelegatedTaskView(
                    t.getId(),
                    t.getTitle(),
                    t.getDescription(),
                    t.getStatus(),
                    t.getResult(),
                    t.getDelegatedTo(),
                    t.getDelegateClaimedAt(),
                    t.getCreatedAt(),
                    t.getUpdatedAt());
        }
    }

    /**
     * Body for claiming a task. {@code worker} is a self-reported name shown on
     * the board so the owner can see who took the job; it is optional.
     */
    public record ClaimRequest(@Size(max = 100) String worker) {
    }

    /**
     * A successful claim: the task, plus the one-time {@code token} that must be
     * presented when reporting the outcome. The token is returned <em>only</em>
     * here, so a later passer-by cannot overwrite the claimer's report.
     */
    public record ClaimResponse(DelegatedTaskView task, String token) {
    }

    /**
     * Body for reporting a delegated task's outcome. {@code status} is one of
     * {@code PENDING} (hand it back), {@code IN_PROGRESS}, {@code RESOLVED} or
     * {@code FAILED}; {@code result} is the free-text answer shown on the board.
     * {@code token} is required once the task has been claimed.
     */
    public record ReportRequest(
            @NotBlank String status,
            @Size(max = 100_000) String result,
            String token) {
    }
}
