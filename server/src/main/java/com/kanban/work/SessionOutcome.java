package com.kanban.work;

/**
 * The raw facts one finished Claude session produced — no verdict attached.
 *
 * <p>This is what a worker reports and what the in-process runner produces, in one shape, so
 * {@link TaskOutcomeService} can read both. Whether these facts add up to Done or Needs
 * Attention is decided in exactly one place; see that class for why.
 *
 * @param abandonedBackgroundWork the session ended while background work it had started was
 *                                still running, so the CLI killed that work — whatever is in
 *                                {@code finalOutput} was written before then
 * @param stopped                 the session was killed because the user pressed Stop
 */
public record SessionOutcome(
        int exitCode,
        boolean timedOut,
        boolean abandonedBackgroundWork,
        boolean stopped,
        String finalOutput,
        Double costUsd,
        Long durationMs) {

    public String outputOrEmpty() {
        return finalOutput == null ? "" : finalOutput;
    }
}
