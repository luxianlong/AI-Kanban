package com.kanban.agent;

/** Where a claim has got to. */
public enum LeaseState {

    /** A worker holds this work and is expected to keep heartbeating. */
    ACTIVE,

    /** The worker reported an outcome; the board has been written. */
    DONE,

    /** The worker handed the work back deliberately (it was shutting down). */
    RELEASED,

    /**
     * The worker stopped heartbeating and the reaper took the work back. Kept rather than
     * deleted so a worker that wakes up late is told its lease is gone instead of silently
     * reporting onto work someone else is now doing.
     */
    EXPIRED
}
