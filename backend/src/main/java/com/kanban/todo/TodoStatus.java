package com.kanban.todo;

/**
 * Lifecycle of a todo item as it flows through the Claude Code worker.
 *
 * <pre>
 *   PENDING ──picked up by worker──▶ IN_PROGRESS ──▶ RESOLVED
 *                                                └──▶ FAILED
 * </pre>
 *
 * <p>Every task starts life queued ("To Do") — there is no separate staging
 * step. Story steps are queued too, but the worker runs them one at a time in
 * chain order (a step waits until every earlier step in its story is RESOLVED),
 * so a story still flows sequentially without a manual promote.
 */
public enum TodoStatus {
    /** Queued ("To Do") and waiting for the worker to pick it up. */
    PENDING,
    /** Currently being worked on by a Claude Code session. */
    IN_PROGRESS,
    /** Claude Code finished and produced a result. */
    RESOLVED,
    /** The worker attempted the item but it errored or timed out. */
    FAILED
}
