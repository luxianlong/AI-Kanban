package com.kanban.protocol;

/**
 * The two clocks one Claude session runs against, as a pair.
 *
 * <p>{@code timeoutSeconds} is the hard ceiling on the whole process;
 * {@code backgroundWaitSeconds} is how long the CLI may wait, after the session's last
 * turn, for background work that session started before killing it. They are one value
 * rather than two arguments because <em>the wait only makes sense against the timeout it
 * belongs to</em>. That relationship has been broken twice already, each time by someone
 * changing one number and not the other: a wait pinned at 1500 against a timeout raised
 * to 3600 killed a step's dynamic workflow with 35 minutes of the step's own allowance
 * still unspent, and the session then reported the "I'll report back" sentence it had
 * written before the kill as its answer.
 *
 * <p>So there is no public way to build one out of a bare number. Ask
 * {@code WorkerProperties.sessionLimits()} for the configured pair, or
 * {@code sessionLimitsWithTimeout(long)} for a longer-lived session — both derive the wait
 * with the same rule, so a caller that lengthens a session cannot forget to lengthen what that
 * session is allowed to wait for.
 *
 * <p>It lives in the protocol because both ends need it: the server computes the pair and the
 * worker applies it, and a worker that received only one of the two would be back in exactly
 * the failure described above.
 *
 * @param timeoutSeconds        how long the process may live, from launch
 * @param backgroundWaitSeconds how long the CLI waits for background work after the last
 *                              turn; {@code 0} means wait indefinitely
 */
public record SessionLimitsDto(long timeoutSeconds, long backgroundWaitSeconds) {
}
