package com.kanban.protocol;

/**
 * The server's answer to a heartbeat — and its only channel for telling a worker anything.
 *
 * <p>A worker may be on a laptop behind NAT, so the server can never dial it. Everything the
 * server needs to say therefore has to be an answer to a call the worker made: the Stop button
 * on the board sets {@link #stopRequested()} on the lease, and the worker kills its own process
 * when it next checks in. Stopping is consequently as prompt as the heartbeat interval, and no
 * more.
 *
 * @param stopRequested   the user pressed Stop; kill the session and report it as stopped
 * @param leaseExpiresAt  epoch millis after which the server will consider this work abandoned
 * @param known           false when the server has never heard of this lease (it expired and
 *                        was reaped, or the server's database was replaced). The worker should
 *                        abandon the run rather than keep paying for work nobody will record.
 */
public record HeartbeatResponse(boolean stopRequested, long leaseExpiresAt, boolean known) {
}
