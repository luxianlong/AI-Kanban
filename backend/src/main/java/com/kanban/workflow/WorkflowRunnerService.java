package com.kanban.workflow;

import com.kanban.worker.ClaudeSessionRunner;
import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.RunningSessionRegistry;
import com.kanban.worker.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Drives workflow executions forward: on each tick it finds the oldest run that
 * still owes work, picks the next node whose inputs are all ready, and runs it as a
 * headless Claude session.
 *
 * <p><strong>Nodes do not share context.</strong> Every node is a brand-new session
 * — nothing is resumed, and no conversation history crosses an edge. The only thing
 * a node inherits is the <em>text</em> its upstream node(s) produced, which is
 * written into its prompt as an INPUT block (see {@link #buildPrompt}). That is what
 * makes a workflow composable: a node's behaviour depends only on its own prompt and
 * the input it is handed, not on what some earlier node happened to talk about.
 *
 * <p><strong>One node per tick.</strong> The tick returns as soon as a node finishes
 * rather than draining the whole graph, so the board's task queue
 * ({@code ClaudeWorkerService}) gets its turn between nodes. Both run on Spring's
 * single scheduler thread, which is deliberate: it means only one Claude process is
 * ever alive, whatever mix of tasks and workflows is queued.
 */
@Service
public class WorkflowRunnerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunnerService.class);

    /**
     * Max characters of one upstream node's output carried into a downstream node's
     * prompt. Generous, because in a workflow the previous output <em>is</em> the
     * task material — but still bounded, so a runaway node can't blow up every node
     * after it.
     */
    static final int INPUT_BUDGET_PER_DEPENDENCY = 12_000;

    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeRuns;
    private final ClaudeSessionRunner runner;
    private final LiveLogRegistry liveLog;
    private final RunningSessionRegistry sessions;
    private final WorkerProperties props;
    private final ReentrantLock tickLock = new ReentrantLock();

    public WorkflowRunnerService(WorkflowExecutionRepository executions, NodeExecutionRepository nodeRuns,
                                 ClaudeSessionRunner runner, LiveLogRegistry liveLog,
                                 RunningSessionRegistry sessions, WorkerProperties props) {
        this.executions = executions;
        this.nodeRuns = nodeRuns;
        this.runner = runner;
        this.liveLog = liveLog;
        this.sessions = sessions;
        this.props = props;
    }

    /**
     * A node left {@link RunState#RUNNING} at startup belongs to a session that died
     * with the previous process. Unlike a board task it is <em>not</em> re-queued:
     * the node may already have had side effects, and re-running it would silently
     * duplicate them halfway through a graph. It is failed instead, which lets the
     * normal "an upstream node didn't succeed" cascade close the run out on the next
     * tick, leaving the finished nodes' outputs readable.
     */
    @PostConstruct
    void recoverOrphans() {
        List<NodeExecution> orphans = nodeRuns.findByStatus(RunState.RUNNING);
        for (NodeExecution run : orphans) {
            run.setStatus(RunState.FAILED);
            run.setError("Interrupted by a backend restart. Not re-run automatically, because "
                    + "this node may already have done part of its work — start the workflow again "
                    + "if you want another attempt.");
            run.setFinishedAt(Instant.now());
            nodeRuns.save(run);
        }
        if (!orphans.isEmpty()) {
            log.warn("Failed {} workflow node run(s) orphaned by a restart.", orphans.size());
        }
    }

    @Scheduled(fixedDelayString = "${workflow.poll-interval-ms:5000}",
            initialDelayString = "${workflow.initial-delay-ms:12000}")
    public void poll() {
        // Gated on the same switch as the board worker: both drive the same CLI on the
        // same machine, so "the worker is off" has to mean workflows are off too.
        if (!props.isEnabled() || sessions.isShuttingDown()) {
            return;
        }
        if (!tickLock.tryLock()) {
            log.debug("Previous workflow tick still running; skipping.");
            return;
        }
        try {
            List<WorkflowExecution> active =
                    executions.findByStatusInOrderByCreatedAtAsc(List.of(RunState.PENDING, RunState.RUNNING));
            for (WorkflowExecution execution : active) {
                if (advance(execution)) {
                    // A node ran; hand the tick back so the task queue isn't starved.
                    return;
                }
            }
        } finally {
            tickLock.unlock();
        }
    }

    /**
     * Move one execution as far as it can go without launching more than one session.
     * Returns {@code true} if a node was actually run on this call.
     */
    private boolean advance(WorkflowExecution execution) {
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        if (runs.isEmpty()) {
            finish(execution, runs, "This run has no nodes.");
            return false;
        }
        if (execution.getStatus() == RunState.PENDING) {
            execution.setStatus(RunState.RUNNING);
            execution.setStartedAt(Instant.now());
            executions.save(execution);
            log.info("Workflow execution #{} ({}) started.", execution.getId(), execution.getWorkflowName());
        }

        Map<String, NodeExecution> byNodeId = new HashMap<>();
        for (NodeExecution run : runs) {
            byNodeId.put(run.getNodeId(), run);
        }
        skipUnreachableWork(runs, byNodeId);

        NodeExecution ready = runs.stream()
                .filter(r -> r.getStatus() == RunState.PENDING && dependenciesSucceeded(r, byNodeId))
                .findFirst()
                .orElse(null);
        if (ready != null) {
            runNode(execution, ready, byNodeId, runs.size());
            return true;
        }
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.RUNNING)) {
            // Only reachable if a session outlived its tick, which shouldn't happen —
            // leave the run alone rather than closing it out under a live node.
            return false;
        }
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.PENDING)) {
            // Nothing ready, nothing running, yet work is left: the snapshot's
            // dependencies don't resolve. Close the run out rather than spinning.
            finish(execution, runs, "Stuck: some nodes never became runnable. "
                    + "Their recorded dependencies don't resolve — re-save the workflow and try again.");
            return false;
        }
        finish(execution, runs, null);
        return false;
    }

    /**
     * Mark every still-queued node whose upstream will never succeed as
     * {@link RunState#SKIPPED}, repeating until it settles so the skip propagates all
     * the way down the branch. Without this a failed node would leave everything
     * behind it queued forever.
     */
    private void skipUnreachableWork(List<NodeExecution> runs, Map<String, NodeExecution> byNodeId) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NodeExecution run : runs) {
                if (run.getStatus() != RunState.PENDING) {
                    continue;
                }
                for (String dependency : run.dependencyIds()) {
                    NodeExecution upstream = byNodeId.get(dependency);
                    boolean deadEnd = upstream == null
                            || (upstream.getStatus().isTerminal() && upstream.getStatus() != RunState.SUCCEEDED);
                    if (deadEnd) {
                        run.setStatus(RunState.SKIPPED);
                        run.setError("Skipped: the node it depends on ("
                                + (upstream == null ? dependency : "\"" + upstream.getNodeName() + "\"")
                                + ") did not succeed.");
                        run.setFinishedAt(Instant.now());
                        nodeRuns.save(run);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private boolean dependenciesSucceeded(NodeExecution run, Map<String, NodeExecution> byNodeId) {
        for (String dependency : run.dependencyIds()) {
            NodeExecution upstream = byNodeId.get(dependency);
            if (upstream == null || upstream.getStatus() != RunState.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /** Run one node's session start to finish and record what it produced. */
    private void runNode(WorkflowExecution execution, NodeExecution run,
                         Map<String, NodeExecution> byNodeId, int totalNodes) {
        String input = assembleInput(execution, run, byNodeId);
        run.setStatus(RunState.RUNNING);
        run.setStartedAt(Instant.now());
        run.setInput(input);
        nodeRuns.save(run);

        String key = LiveLogRegistry.nodeKey(run.getId());
        liveLog.start(key);
        liveLog.append(key, "● Node \"" + run.getNodeName() + "\" ("
                + (run.getOrderIndex() + 1) + "/" + totalNodes + ") started.");
        log.info("Workflow execution #{}: running node \"{}\".", execution.getId(), run.getNodeName());

        try {
            ProcessResult result = runner.run(key, buildPrompt(execution, run, input, totalNodes));
            run.setCostUsd(result.costUsd());
            run.setDurationMs(result.durationMs());

            if (sessions.isShuttingDown()) {
                // Shutdown killed this session — it neither failed nor timed out. Leave
                // the row RUNNING so recoverOrphans() records it on the next start.
                sessions.consumeStopRequested(key);
                liveLog.append(key, "■ Backend shutting down — this node was interrupted.");
                nodeRuns.save(run);
                return;
            }
            if (sessions.consumeStopRequested(key)) {
                // The user cancelled: WorkflowService.cancel already wrote the terminal
                // state for the whole run, so don't overwrite it with a fake failure.
                liveLog.append(key, "✗ Cancelled.");
                return;
            }
            String output = result.finalOutput() == null ? "" : result.finalOutput();
            if (result.timedOut()) {
                failNode(run, key, "Timed out after " + props.getTimeoutSeconds() + "s.");
            } else if (result.exitCode() != 0) {
                failNode(run, key, "Claude exited with code " + result.exitCode() + ".\n\n" + output);
            } else if (output.contains(ClaudeSessionRunner.FAIL_SENTINEL)) {
                failNode(run, key, output);
            } else {
                run.setStatus(RunState.SUCCEEDED);
                run.setOutput(output.isBlank() ? "(no output)" : output);
                run.setFinishedAt(Instant.now());
                nodeRuns.save(run);
                liveLog.append(key, "✔ Node done.");
            }
        } catch (Exception e) {
            log.error("Workflow execution #{}: node \"{}\" errored", execution.getId(), run.getNodeName(), e);
            failNode(run, key, "Runner error: " + e.getMessage());
        }
    }

    private void failNode(NodeExecution run, String key, String message) {
        run.setStatus(RunState.FAILED);
        run.setError(message);
        run.setFinishedAt(Instant.now());
        nodeRuns.save(run);
        liveLog.append(key, "✗ " + message.lines().findFirst().orElse("Node failed."));
    }

    /** Close an execution out, deriving its outcome from the nodes' own outcomes. */
    private void finish(WorkflowExecution execution, List<NodeExecution> runs, String error) {
        RunState status = RunState.SUCCEEDED;
        if (error != null) {
            status = RunState.FAILED;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == RunState.FAILED)) {
            status = RunState.FAILED;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == RunState.CANCELLED)) {
            status = RunState.CANCELLED;
        }
        double cost = 0;
        boolean anyCost = false;
        for (NodeExecution run : runs) {
            if (run.getCostUsd() != null) {
                cost += run.getCostUsd();
                anyCost = true;
            }
        }
        execution.setStatus(status);
        execution.setError(error);
        execution.setCostUsd(anyCost ? cost : null);
        execution.setFinishedAt(Instant.now());
        executions.save(execution);
        log.info("Workflow execution #{} finished: {}.", execution.getId(), status);
    }

    // --- the prompt ------------------------------------------------------

    /**
     * The text handed to this node from upstream — the <em>only</em> thing that
     * crosses an edge, since each node runs in its own fresh session.
     *
     * <p>The entry node gets whatever the user typed when starting the run (possibly
     * nothing). Every other node gets its dependencies' outputs, each labelled with
     * the node it came from so a node with two parents can tell them apart.
     */
    String assembleInput(WorkflowExecution execution, NodeExecution run,
                         Map<String, NodeExecution> byNodeId) {
        List<String> dependencies = run.dependencyIds();
        if (dependencies.isEmpty()) {
            return execution.getInput() == null ? "" : execution.getInput();
        }
        StringBuilder sb = new StringBuilder();
        for (String dependency : dependencies) {
            NodeExecution upstream = byNodeId.get(dependency);
            if (upstream == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("— Output of node \"").append(upstream.getNodeName()).append("\":\n");
            String output = upstream.getOutput();
            if (output == null || output.isBlank()) {
                sb.append("(this node produced no output)\n");
            } else if (output.length() > INPUT_BUDGET_PER_DEPENDENCY) {
                sb.append(output, 0, INPUT_BUDGET_PER_DEPENDENCY).append("\n…(truncated)\n");
            } else {
                sb.append(output).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The full prompt for one node: what the node is, the input it was handed, and
     * the user's own prompt for it — in that order, so the instructions are the last
     * thing read.
     *
     * <p>It says explicitly that the reply becomes the next node's input, because a
     * session that doesn't know that tends to answer conversationally ("I've updated
     * the file") instead of producing the material the rest of the graph needs.
     */
    String buildPrompt(WorkflowExecution execution, NodeExecution run, String input, int totalNodes) {
        StringBuilder sb = new StringBuilder();
        boolean isEntry = run.dependencyIds().isEmpty();

        sb.append("You are running ONE node of a workflow named \"")
                .append(execution.getWorkflowName()).append("\" (run #").append(execution.getId())
                .append("). This node is \"").append(run.getNodeName()).append("\", step ")
                .append(run.getOrderIndex() + 1).append(" of ").append(totalNodes).append(".\n\n");

        sb.append("How a workflow node works:\n");
        sb.append("- You are a FRESH session. You cannot see what any other node did, ")
                .append("beyond the INPUT reproduced below — there is no shared history to recall.\n");
        sb.append("- Your final reply is passed on verbatim as the INPUT of the node(s) that come ")
                .append("after this one. Make it complete and self-contained: put the actual ")
                .append("material in the reply rather than pointing at where you left it.\n");
        sb.append("- Do only what THIS node asks. The later nodes have their own instructions.\n");
        sb.append("- If this node genuinely cannot be completed, end your reply with a line ")
                .append("containing exactly ").append(ClaudeSessionRunner.FAIL_SENTINEL)
                .append(" followed by a brief reason; the rest of the workflow is then skipped.\n\n");

        // Same guardrail a general board task gets: a workflow node produces output and
        // resources, it does not rebuild or redeploy the app it happens to run inside.
        sb.append("You MAY create or change RESOURCE/output files your work produces (documents, ")
                .append("research artifacts, data files, images — e.g. under docs/ or research/). ")
                .append("Do NOT modify the Kanban application's own code, do NOT run ")
                .append("scripts/deploy.sh, and do NOT rebuild or restart the website or its backend.\n\n");

        sb.append("INPUT — ");
        if (isEntry) {
            sb.append("this is the workflow's ENTRY node, so the input is what the user supplied ")
                    .append("when starting the run:\n");
        } else {
            sb.append("the output of the node(s) feeding this one:\n");
        }
        sb.append(input == null || input.isBlank()
                ? (isEntry ? "(the user started this run without any input)\n" : "(nothing)\n")
                : input + "\n");

        sb.append("\nINSTRUCTIONS FOR THIS NODE (\"").append(run.getNodeName()).append("\"):\n");
        sb.append(run.getPrompt() == null || run.getPrompt().isBlank()
                ? "(this node has no instructions of its own — pass the input through, tidied up)\n"
                : run.getPrompt() + "\n");
        return sb.toString();
    }
}
