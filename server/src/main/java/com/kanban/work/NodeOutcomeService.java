package com.kanban.work;

import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Turns what a node's session produced into what the run says about it.
 *
 * <p>The task counterpart is {@link TaskOutcomeService}, and the reasoning is the same: the
 * worker reports facts and the server decides what they mean, so a worker jar a deploy cannot
 * reach never holds an opinion about a result. The branches differ from a task's in one way
 * worth noticing — a node's failure is not just its own. Its output is what crosses every edge
 * below it, so recording a session as SUCCEEDED when it is holding a promise rather than an
 * answer does not spoil one step, it spoils every step downstream, which then have nothing to
 * work on and say so in their own outputs.
 */
@Service
public class NodeOutcomeService {

    /** Agents print this to mark their work failed even when the CLI exits 0. */
    public static final String FAIL_SENTINEL = TaskPromptBuilder.FAIL_SENTINEL;

    private final NodeExecutionRepository nodeRuns;

    public NodeOutcomeService(NodeExecutionRepository nodeRuns) {
        this.nodeRuns = nodeRuns;
    }

    /** What was decided, so the caller can put the same words in the node's live log. */
    public record Applied(RunState status, String logLine) {
    }

    /**
     * Record the outcome of one node's session.
     *
     * <p>The timeouts are passed in rather than read from configuration because this node ran
     * under the limits its work unit carried — an ultracode step's are a multiple of everyone
     * else's — and the message has to name the clock the session actually ran on.
     */
    public Applied apply(NodeExecution run, SessionOutcome outcome, long timeoutSeconds,
                         long backgroundWaitSeconds) {
        // Added to, not replaced: on a retried node these are what the node has cost across
        // every attempt. An attempt that failed was billed all the same, and a total that
        // quietly forgot it would understate the run.
        run.setCostUsd(plus(run.getCostUsd(), outcome.costUsd()));
        run.setDurationMs(plus(run.getDurationMs(), outcome.durationMs()));

        String output = outcome.outputOrEmpty();

        if (outcome.stopped()) {
            // The user cancelled: WorkflowService.cancel already wrote the terminal state for
            // the whole run, so don't overwrite it with a fake failure.
            nodeRuns.save(run);
            return new Applied(run.getStatus(), "✗ Cancelled.");
        }
        if (outcome.timedOut()) {
            return fail(run, "Timed out after " + timeoutSeconds + "s.");
        }
        if (outcome.exitCode() != 0) {
            return fail(run, "Claude exited with code " + outcome.exitCode() + ".\n\n" + output);
        }
        if (outcome.abandonedBackgroundWork()) {
            // The step handed its real work to something running in the background — a dynamic
            // workflow is the usual case, which is exactly what the ultracode effort level asks
            // for — and the CLI killed that work when it ran out of patience. The text below was
            // written before the kill, so it is a promise to report back rather than an answer.
            return fail(run, "The step ended while background work it had started was"
                    + " still running, so the CLI terminated that work after "
                    + backgroundWaitSeconds + "s.\n\nWhat the step replied was"
                    + " written before then and describes results it never received, so it"
                    + " is not passed on:\n\n" + output);
        }
        if (output.contains(FAIL_SENTINEL)) {
            return fail(run, output);
        }

        run.setStatus(RunState.SUCCEEDED);
        run.setOutput(output.isBlank() ? "(no output)" : output);
        run.setFinishedAt(Instant.now());
        nodeRuns.save(run);
        return new Applied(RunState.SUCCEEDED, "✔ Node done.");
    }

    private Applied fail(NodeExecution run, String message) {
        run.setStatus(RunState.FAILED);
        run.setError(message);
        run.setFinishedAt(Instant.now());
        nodeRuns.save(run);
        return new Applied(RunState.FAILED, "✗ " + message.lines().findFirst().orElse("Node failed."));
    }

    private static Double plus(Double sofar, Double attempt) {
        if (attempt == null) {
            return sofar;
        }
        return sofar == null ? attempt : sofar + attempt;
    }

    private static Long plus(Long sofar, Long attempt) {
        if (attempt == null) {
            return sofar;
        }
        return sofar == null ? attempt : sofar + attempt;
    }
}
