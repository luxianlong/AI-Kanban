package com.kanban.worker;

import com.kanban.worker.session.SessionLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects a session's rendered lines between heartbeats.
 *
 * <p>The server's transcript store used to be written to directly, line by line, as the CLI
 * produced them. Over a network that would be one request per line, so lines are batched here
 * and drained on each check-in instead. The visible cost is that the card's live log runs one
 * heartbeat interval behind, which is the trade the split makes.
 *
 * <p>Capped, because a chatty session must not be able to exhaust a worker's heap while the
 * server is unreachable — beyond the cap the oldest lines are dropped and a marker is left in
 * their place, since it is recent activity a watcher cares about.
 */
public class LogBuffer implements SessionLog {

    private static final int MAX_PENDING_LINES = 5000;

    private final List<String> pending = new ArrayList<>();
    private boolean dropped;

    @Override
    public synchronized void append(String sessionKey, String line) {
        if (pending.size() >= MAX_PENDING_LINES) {
            pending.remove(0);
            dropped = true;
        }
        pending.add(line);
    }

    /** Take everything buffered so far, leaving the buffer empty. */
    public synchronized List<String> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> batch = new ArrayList<>(pending.size() + 1);
        if (dropped) {
            batch.add("… (earlier output dropped: the worker buffered more than "
                    + MAX_PENDING_LINES + " lines without reaching the server)");
            dropped = false;
        }
        batch.addAll(pending);
        pending.clear();
        return batch;
    }
}
