package com.kanban.protocol;

import java.util.List;

/**
 * "Still working" — and, on the way, whatever the session has said since the last one.
 *
 * <p>Log lines ride along with the heartbeat rather than having an endpoint of their own,
 * because they are the evidence that the worker is alive: one call keeps the lease and moves
 * the live log forward together, and there is no way for a worker to look healthy while the
 * card sits silent.
 *
 * @param seq      counts heartbeats for this lease from 1. The server ignores a heartbeat it
 *                 has already seen, so a retry after a network wobble cannot double-append
 *                 the same lines to the transcript.
 * @param logLines rendered lines, in order, since the previous heartbeat. May be empty.
 */
public record HeartbeatRequest(long seq, List<String> logLines) {
}
