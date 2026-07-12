package com.kanban.todo;

/**
 * Lifecycle of a todo item as it flows through the Claude Code worker.
 *
 * <pre>
 *   PENDING ──picked up by worker──▶ IN_PROGRESS ──▶ RESOLVED
 *                                                └──▶ FAILED
 * </pre>
 */
public enum TodoStatus {
    /** Created by a user, waiting for the worker to pick it up. */
    PENDING,
    /** Currently being worked on by a Claude Code session. */
    IN_PROGRESS,
    /** Claude Code finished and produced a result. */
    RESOLVED,
    /** The worker attempted the item but it errored or timed out. */
    FAILED
}
