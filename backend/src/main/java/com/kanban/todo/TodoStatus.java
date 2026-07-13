package com.kanban.todo;

/**
 * Lifecycle of a todo item as it flows through the Claude Code worker.
 *
 * <pre>
 *   NEW ──promoted by user──▶ PENDING ──picked up by worker──▶ IN_PROGRESS ──▶ RESOLVED
 *                                                                          └──▶ FAILED
 * </pre>
 */
public enum TodoStatus {
    /**
     * Staged by the user (typically a story step) but not yet queued. The worker
     * never picks up a NEW item — the user promotes it to {@link #PENDING} (by
     * clicking "→ To Do" or dragging it into the To Do column) when it's ready.
     */
    NEW,
    /** Queued ("To Do") and waiting for the worker to pick it up. */
    PENDING,
    /** Currently being worked on by a Claude Code session. */
    IN_PROGRESS,
    /** Claude Code finished and produced a result. */
    RESOLVED,
    /** The worker attempted the item but it errored or timed out. */
    FAILED
}
