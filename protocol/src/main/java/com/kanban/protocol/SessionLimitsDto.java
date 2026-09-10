package com.kanban.protocol;

/**
 * The two clocks a session runs under, computed by the server and applied by the worker.
 *
 * <p>They travel together on purpose. {@code timeoutSeconds} is how long the session may
 * live; {@code backgroundWaitSeconds} is how long the CLI may wait, after the last turn, for
 * background work the session started before killing it. Raising one without the other is a
 * mismatch that does not fail loudly — a step allowed to live for hours whose background work
 * is killed after twenty-five minutes reports the sentence it wrote before the kill ("I'll
 * report back when it lands") as its answer, and that is what gets recorded as the result.
 *
 * @param timeoutSeconds        hard ceiling on the whole session
 * @param backgroundWaitSeconds passed to the CLI as {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS};
 *                              0 means wait indefinitely
 */
public record SessionLimitsDto(long timeoutSeconds, long backgroundWaitSeconds) {
}
