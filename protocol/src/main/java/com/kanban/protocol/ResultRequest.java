package com.kanban.protocol;

/**
 * What one finished session produced — <em>facts only</em>.
 *
 * <p>Deliberately not a verdict. Whether a run counts as resolved or failed is decided by the
 * server, from exactly these fields, using the same rules the in-process worker has always
 * used. The worker does not know that a non-empty {@code finalOutput} containing a particular
 * sentinel means semantic failure, or that {@code abandonedBackgroundWork} makes a clean exit
 * code meaningless. That judgement lives next to the database it is written into, in one copy,
 * so an old worker on another machine cannot disagree with the board about what happened.
 *
 * @param abandonedBackgroundWork the session ended while background work it had started was
 *                                still running and the CLI killed that work; whatever is in
 *                                {@code finalOutput} was written before then and describes
 *                                results it never received
 * @param stopped                 the worker killed the session because a heartbeat told it to
 */
public record ResultRequest(
        int exitCode,
        boolean timedOut,
        boolean abandonedBackgroundWork,
        boolean stopped,
        String finalOutput,
        Double costUsd,
        Long durationMs) {
}
