package com.kanban.protocol;

/**
 * Constants naming the contract between the server and a worker.
 *
 * <p>The version exists because a worker is the one part of this system a deploy cannot
 * reach: {@code scripts/deploy.sh} rebuilds and restarts the server, but a worker jar
 * sitting on someone else's laptop keeps running whatever it was started with. A worker
 * sends {@link #VERSION} when it claims, so the server can refuse a stale one with a
 * message that says so rather than handing it a work unit it will misread.
 *
 * <p>Bump it whenever a field changes meaning or disappears — adding an optional field
 * that an older worker can ignore does not need a bump.
 */
public final class AgentProtocol {

    /** Wire-format version. See the class comment for when to change it. */
    public static final int VERSION = 1;

    /** Base path of the agent API on the server. Exempt from the site password gate. */
    public static final String BASE_PATH = "/api/agent";

    /** Header carrying the shared worker token, as {@code Bearer <token>}. */
    public static final String AUTH_HEADER = "Authorization";

    private AgentProtocol() {
    }
}
