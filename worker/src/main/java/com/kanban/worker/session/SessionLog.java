package com.kanban.worker.session;

/**
 * Where a running session's rendered output goes.
 *
 * <p>An interface only because the runner used to write straight into the server's in-memory
 * transcript store, which a worker on another machine plainly cannot reach. The worker's
 * implementation buffers lines and ships them up on the next heartbeat instead; nothing about
 * the rendering changes.
 */
@FunctionalInterface
public interface SessionLog {

    void append(String sessionKey, String line);
}
