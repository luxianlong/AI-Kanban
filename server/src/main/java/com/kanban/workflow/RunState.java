package com.kanban.workflow;

/**
 * Lifecycle of a workflow execution and of each node run inside it.
 *
 * <pre>
 *   PENDING ──ready to run──▶ RUNNING ──▶ SUCCEEDED
 *      │                         └──────▶ FAILED
 *      ├──an upstream node failed──▶ SKIPPED
 *      └──user cancelled the run───▶ CANCELLED
 * </pre>
 */
public enum RunState {
    /** Queued: the execution hasn't started, or the node's inputs aren't ready yet. */
    PENDING,
    /** A Claude session is running for this node (or the execution has live nodes). */
    RUNNING,
    /** Finished and produced an output. */
    SUCCEEDED,
    /** The session errored, timed out, or reported the failure sentinel. */
    FAILED,
    /**
     * Never run, and never will be in this execution: an upstream node failed or was
     * cancelled, or the node isn't reachable from the workflow's entry node.
     */
    SKIPPED,
    /** Stopped on the user's request. */
    CANCELLED;

    /** Whether this state is final — nothing more will happen to the run. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
