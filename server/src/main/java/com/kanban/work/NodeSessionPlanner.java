package com.kanban.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kanban.workflow.EffortLevel;
import com.kanban.protocol.SessionLimitsDto;
import com.kanban.worker.WorkerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides the flags and the clocks for one workflow node's session.
 *
 * <p>Extracted from the workflow runner because it is now the server's job for every worker,
 * local or remote. A worker is handed the finished argument list and the two timeouts; it never
 * learns what an effort level is, which is what keeps an old worker from applying an old idea
 * of one.
 */
@Component
public class NodeSessionPlanner {

    /**
     * Settings key that refuses file-tool reads (Read, Grep, Glob, LSP) outside the
     * session's working directory. Paired with each node getting a directory of its own, this
     * is what makes a node unable to see anything but its own files.
     *
     * <p>It is the right key rather than a set of {@code permissions.deny} globs because
     * the CLI applies it <em>in every permission mode</em> — including the
     * {@code bypassPermissions} that {@code worker.extra-args} switches on with
     * {@code --dangerously-skip-permissions}. A deny list would also have to enumerate
     * everything on the box; this one names what is allowed instead, so a path nobody
     * thought of is out by default.
     */
    public static final String FENCE_KEY = "blockReadsOutsideWorkingDirectories";

    private final WorkerProperties props;
    private final boolean fenceReads;
    private final double ultracodeTimeoutMultiplier;

    public NodeSessionPlanner(WorkerProperties props,
                              @Value("${workflow.node-fence-reads:true}") boolean fenceReads,
                              @Value("${workflow.ultracode-timeout-multiplier:3}")
                              double ultracodeTimeoutMultiplier) {
        this.props = props;
        this.fenceReads = fenceReads;
        // Never below 1: ultracode is strictly more work than the level below it, so a
        // configured 0.5 is a typo, and honouring it would give the step that fans out
        // less time than the step that does not.
        this.ultracodeTimeoutMultiplier = Math.max(1.0, ultracodeTimeoutMultiplier);
    }

    /**
     * The directory key a node's session runs under: one per <em>node run</em>, never shared.
     *
     * <p>A board task may legitimately be about improving the project it runs in; a workflow
     * node is not — it transforms text — so it never gets the repo. It is keyed on the node run
     * and not just the execution because that is what makes the read fence mean anything per
     * <em>step</em>: nodes of one run used to share a directory, so the fence drew its line
     * around the whole graph and a step could still read the scratch files of the step before
     * it. That is the leak most likely to actually happen here — a node that writes its
     * material to a file instead of answering with it, and a node below that goes looking for
     * the file rather than reading the input it was handed.
     *
     * <p>Keyed on the node run's id rather than its node id, so a re-run of one step (which
     * reuses the row and bumps {@code attempt}) lands back in its own directory and not in a
     * sibling's.
     */
    public static String workDirKeyFor(Long executionId, Long nodeRunId) {
        return "workflow/run-" + executionId + "/node-" + nodeRunId;
    }

    public NodeSessionSpec planFor(EffortLevel effort) {
        return new NodeSessionSpec(sessionArgsFor(effort), limitsFor(effort));
    }

    /**
     * How long this step's session may live, and how long it may wait for work it hands
     * to the background — the configured clocks for every level but one.
     *
     * <p>{@link EffortLevel#ULTRACODE} gets a multiple of them, because it is the one
     * level that changes <em>what a step is</em> rather than how long it thinks. The
     * other levels spend their whole allowance inside one session's turns, so the timeout
     * that fits a step at {@code max} fits a step at {@code low} too — it is the same
     * shape of work, done more carefully. An ultracode step instead hands its real work
     * to sub-sessions and then waits: its wall-clock is that nested work's wall-clock
     * plus its own, and each of those sub-sessions is doing a job the size of an
     * ordinary step. Measuring it against one step's ceiling is measuring the wrong
     * thing, and the step is killed at the ceiling holding nothing — having been billed
     * for everything the sub-sessions did before the kill.
     *
     * <p>A <em>multiplier</em> and not a number of seconds, because the live server is
     * launched with a timeout that overrides the one in the yml: an absolute figure would be
     * right in one of those worlds and wrong in the other, and could quietly end up
     * <em>shorter</em> than an ordinary step's timeout. The background wait scales with it
     * from the same {@link WorkerProperties#sessionLimitsWithTimeout}, so lengthening the step
     * lengthens what the step may wait for — half of this change would be a step alive for
     * three hours whose sub-sessions are killed at fifty-five minutes, and that does not fail,
     * it reports the promise the step wrote before the kill.
     */
    public SessionLimitsDto limitsFor(EffortLevel effort) {
        if (effort != EffortLevel.ULTRACODE) {
            return props.sessionLimits();
        }
        return props.sessionLimitsWithTimeout(
                Math.round(props.getTimeoutSeconds() * ultracodeTimeoutMultiplier));
    }

    /**
     * Extra CLI args for this node's session: the read fence, and this node's own effort
     * level when it has one. The same for every node — which step a run starts from
     * makes no difference here.
     *
     * <p>The fence travels in a {@code --settings} blob, built with Jackson rather than
     * concatenated: in {@code --print} mode a settings value that fails to parse is
     * <em>ignored without a word</em>, so a stray escape would not fail the build or log
     * anything, it would just quietly unfence every node. Anything else that ever needs
     * to be a setting has to go in this same blob — {@code --settings} takes a single
     * value, so a second occurrence replaces the first rather than adding to it.
     *
     * <p>Effort is its own {@code --effort} flag instead, because the CLI has one.
     */
    public List<String> sessionArgsFor(EffortLevel effort) {
        ObjectNode settings = JsonNodeFactory.instance.objectNode();
        if (fenceReads) {
            settings.putObject("permissions").put(FENCE_KEY, true);
        }
        List<String> args = new ArrayList<>();
        if (!settings.isEmpty()) {
            args.add("--settings");
            args.add(settings.toString());
        }
        if (effort != null) {
            args.add("--effort");
            args.add(effort.cliValue());
        }
        return List.copyOf(args);
    }
}
