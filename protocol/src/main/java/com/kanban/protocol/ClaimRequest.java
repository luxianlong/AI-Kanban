package com.kanban.protocol;

import java.util.List;

/**
 * A worker asking for something to do.
 *
 * @param worker       a name for this worker, shown on the board so a card says where it ran
 * @param capabilities what this worker is willing and able to run — see {@link Capabilities}
 * @param protocolVersion {@link AgentProtocol#VERSION} as the worker was built with. A deploy
 *                        cannot reach a worker jar on someone else's laptop, so the server
 *                        checks this and refuses a stale worker with a message that says so,
 *                        rather than handing it a unit it will misread.
 */
public record ClaimRequest(String worker, List<String> capabilities, int protocolVersion) {
}
