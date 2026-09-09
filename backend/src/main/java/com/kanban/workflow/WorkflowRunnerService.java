package com.kanban.workflow;

import com.kanban.worker.ClaudeSessionRunner;
import com.kanban.worker.ClaudeSessionRunner.ProcessResult;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.RunningSessionRegistry;
import com.kanban.worker.WorkerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Drives workflow executions forward: on each tick it finds the oldest runs that
 * still owe work and gives each one a lane, which picks that run's next node whose
 * inputs are all ready and runs it as a headless Claude session.
 *
 * <p><strong>Nodes do not share context.</strong> Every node is a brand-new session
 * — nothing is resumed, and no conversation history crosses an edge. The only thing
 * a node inherits is the <em>text</em> its upstream node(s) produced, which is
 * handed to it as its prompt (see {@link #buildPrompt}). That is what
 * makes a workflow composable: a node's behaviour depends only on its own prompt and
 * the input it is handed, not on what some earlier node happened to talk about.
 *
 * <p><strong>Runs go in parallel; a single run stays sequential.</strong> The tick does
 * not run anything itself — it hands each eligible execution to a lane of a small pool
 * ({@code workflow.max-concurrent-executions}) and returns immediately, so a node that
 * takes nine minutes no longer holds the scheduler thread. Different runs of the same
 * workflow, and runs of different workflows, therefore make progress at the same time.
 *
 * <p>What is deliberately <em>not</em> parallel is one execution's own graph: an
 * execution occupies at most one lane, so it still advances one node at a time even
 * where its DAG has independent branches. That is what keeps the claim of a node
 * correct without any locking or {@code @Version} column — an execution is dispatched
 * by the single scheduler thread and only after it is added to {@link #inFlight}, so
 * exactly one thread ever reads or writes that run's node rows, and two lanes can never
 * pick the same {@code PENDING} node and pay for it twice. Widening this to run sibling
 * branches concurrently would need that claim to become atomic first.
 *
 * <p>This poller and {@code ClaudeWorkerService} also get a scheduler thread each
 * ({@code spring.task.scheduling.pool.size}), so a long board task never freezes a
 * workflow or the other way round. Peak concurrent Claude processes on the machine is
 * therefore {@code max-concurrent-executions} + one board task + whatever
 * {@code AiStoryClassifier} has on its own pool; at roughly 270 MB each that is the
 * number to keep an eye on when raising the setting.
 */
@Service
public class WorkflowRunnerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunnerService.class);

    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeRuns;
    private final ClaudeSessionRunner runner;
    private final LiveLogRegistry liveLog;
    private final RunningSessionRegistry sessions;
    private final WorkerProperties props;
    private final ReentrantLock tickLock = new ReentrantLock();

    /** How many executions may have a node in flight at once. */
    private final int lanes;

    /**
     * One thread per lane. Sized to {@link #lanes} and only ever handed as many tasks
     * as there are free lanes, so nothing queues here: a submitted execution starts
     * running immediately.
     */
    private final ExecutorService pool;

    /**
     * The executions that currently occupy a lane. Added by the scheduler thread
     * <em>before</em> the lane is handed the work and removed by the lane when it is
     * done, which is what makes "one thread per execution" true rather than likely.
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * How many executions currently occupy a lane. Visible for tests: a lane releases
     * its claim just <em>after</em> it has written the node's result, so a test that
     * waits only on the stored state can tick again while the claim is still held and
     * see nothing dispatched.
     */
    int lanesInUse() {
        return inFlight.size();
    }

    public WorkflowRunnerService(WorkflowExecutionRepository executions, NodeExecutionRepository nodeRuns,
                                 ClaudeSessionRunner runner, LiveLogRegistry liveLog,
                                 RunningSessionRegistry sessions, WorkerProperties props,
                                 @Value("${workflow.max-concurrent-executions:2}") int maxConcurrentExecutions) {
        this.executions = executions;
        this.nodeRuns = nodeRuns;
        this.runner = runner;
        this.liveLog = liveLog;
        this.sessions = sessions;
        this.props = props;
        this.lanes = Math.max(1, maxConcurrentExecutions);
        this.pool = Executors.newFixedThreadPool(this.lanes, new LaneThreads());
    }

    /** Named daemon threads, so a stuck lane is identifiable in a thread dump. */
    private static final class LaneThreads implements java.util.concurrent.ThreadFactory {
        private int next = 1;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "workflow-lane-" + next++);
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Stop handing out lanes on the way down. The sessions themselves are killed by
     * {@code ClaudeWorkerService}'s shutdown hook (both kinds share one
     * {@link RunningSessionRegistry}), which makes each lane's {@code waitFor} return;
     * the lane then sees {@link RunningSessionRegistry#isShuttingDown()} and leaves its
     * node row RUNNING for {@link #recoverOrphans()} to record on the next start.
     *
     * <p>Deliberately {@code shutdown()} and not {@code shutdownNow()}: interrupting a
     * lane mid-session would surface as a generic runner error and record a node as
     * genuinely failed, when what actually happened is a restart.
     */
    @PreDestroy
    void shutdownLanes() {
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A node left {@link RunState#RUNNING} at startup belongs to a session that died
     * with the previous process. Unlike a board task it is <em>not</em> re-queued:
     * the node may already have had side effects, and re-running it would silently
     * duplicate them halfway through a graph. It is failed instead, which lets the
     * normal "an upstream node didn't succeed" cascade close the run out on the next
     * tick, leaving the finished nodes' outputs readable — and leaving the run in
     * exactly the state Retry is for, if the user decides another attempt is safe.
     */
    @PostConstruct
    void recoverOrphans() {
        List<NodeExecution> orphans = nodeRuns.findByStatus(RunState.RUNNING);
        for (NodeExecution run : orphans) {
            run.setStatus(RunState.FAILED);
            run.setError("Interrupted by a backend restart. Not re-run automatically, because "
                    + "this node may already have done part of its work — retry the run if you "
                    + "decide another attempt is safe.");
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
            for (Long executionId : dispatchable(active, inFlight, lanes)) {
                // Claimed here, on the one thread that dispatches, and released by the
                // lane in its own finally — so the claim can't be lost or doubled.
                inFlight.add(executionId);
                try {
                    pool.execute(() -> {
                        try {
                            advanceById(executionId);
                        } catch (RuntimeException e) {
                            log.error("Workflow execution #{}: lane errored", executionId, e);
                        } finally {
                            inFlight.remove(executionId);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // The pool closed between the shutdown check and here.
                    inFlight.remove(executionId);
                    return;
                }
            }
        } finally {
            tickLock.unlock();
        }
    }

    /**
     * Which executions this tick should start work on: the oldest ones that aren't
     * already occupying a lane, capped at the number of free lanes.
     *
     * <p>Oldest-first is what keeps the queue fair across workflows — a workflow that
     * is started twice does not get both lanes ahead of a run that has been waiting
     * longer. Runs that are already in flight are skipped rather than counted against
     * the cap twice, which is also why a run that finished its node between two ticks
     * is picked straight back up on the next one.
     */
    static List<Long> dispatchable(List<WorkflowExecution> active, Set<Long> inFlight, int lanes) {
        List<Long> toStart = new ArrayList<>();
        int free = lanes - inFlight.size();
        for (WorkflowExecution execution : active) {
            if (free <= 0) {
                break;
            }
            if (inFlight.contains(execution.getId())) {
                continue;
            }
            toStart.add(execution.getId());
            free--;
        }
        return toStart;
    }

    /**
     * Advance one execution from its <em>current</em> stored state.
     *
     * <p>Re-read rather than trusting the row the tick listed, because a user can
     * cancel or delete a run in the moment between the two. Cancelling writes the
     * terminal state for the whole run, and an advance working from the stale copy
     * would put it back to RUNNING and then close it out as if it had succeeded.
     */
    private void advanceById(Long executionId) {
        WorkflowExecution execution = executions.findById(executionId).orElse(null);
        if (execution == null || execution.getStatus().isTerminal()) {
            return;
        }
        advance(execution);
    }

    /**
     * Run at most one of this execution's nodes, on the lane that called in — one node
     * per call rather than draining the graph, so the lane is handed back between nodes,
     * a cancel lands promptly, and the run's state on disk is never more than one node
     * ahead of what the page shows.
     */
    private void advance(WorkflowExecution execution) {
        List<NodeExecution> runs = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(execution.getId());
        if (runs.isEmpty()) {
            finish(execution, runs, "This run has no nodes.");
            return;
        }
        if (execution.getStatus() == RunState.PENDING) {
            execution.setStatus(RunState.RUNNING);
            if (execution.getStartedAt() == null) {
                // Only the first time. A retried run is queued again, but it still
                // began when it began — restamping it would erase how long it has
                // really been going.
                execution.setStartedAt(Instant.now());
            }
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
            return;
        }
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.RUNNING)) {
            // Unreachable in normal operation: this lane owns the run and runs its
            // node synchronously, so nothing of this run's can still be RUNNING here.
            // Leave the run alone rather than closing it out under a live node.
            return;
        }
        if (runs.stream().anyMatch(r -> r.getStatus() == RunState.PENDING)) {
            // Nothing ready, nothing running, yet work is left: the snapshot's
            // dependencies don't resolve. Close the run out rather than spinning.
            finish(execution, runs, "Stuck: some nodes never became runnable. "
                    + "Their recorded dependencies don't resolve — re-save the workflow and try again.");
            return;
        }
        finish(execution, runs, null);
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
                + (run.getOrderIndex() + 1) + "/" + totalNodes + ")"
                + (run.getAttempt() > 1 ? ", attempt " + run.getAttempt() + "," : "")
                + " started.");
        log.info("Workflow execution #{}: running node \"{}\".", execution.getId(), run.getNodeName());

        try {
            ProcessResult result = runner.run(key, buildPrompt(run, input), workDirFor(execution));
            // Added to, not replaced: on a retried node these are what the node has
            // cost across every attempt. An attempt that failed was billed all the
            // same, and a total that quietly forgot it would understate the run.
            run.setCostUsd(plus(run.getCostUsd(), result.costUsd()));
            run.setDurationMs(plus(run.getDurationMs(), result.durationMs()));

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
            if (sessions.isShuttingDown()) {
                // Same case as the clean return above, reached the other way: a lane
                // interrupted on the way down. Leave the row RUNNING so recoverOrphans()
                // records it as a restart rather than as a genuine failure.
                liveLog.append(key, "■ Backend shutting down — this node was interrupted.");
                return;
            }
            log.error("Workflow execution #{}: node \"{}\" errored", execution.getId(), run.getNodeName(), e);
            failNode(run, key, "Runner error: " + e.getMessage());
        }
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
            } else {
                sb.append(output).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The whole prompt for one node: the text it was handed, then the node's own
     * instructions — and nothing else.
     *
     * <p>Deliberately free of scaffolding. Everything the runner used to prepend (the
     * node's name and place in the graph, "you are a fresh session", the failure
     * sentinel, the guardrail that named {@code docs/} and {@code research/}) was the
     * only concrete filesystem hint a node ever saw, and nodes acted on it: they wrote
     * their material into {@code research/} instead of replying with it, and the nodes
     * downstream went hunting through that directory rather than reading the input they
     * had been given. A node now sees exactly what the user wrote for it, plus what
     * came across its edges.
     */
    String buildPrompt(NodeExecution run, String input) {
        String instructions = run.getPrompt() == null ? "" : run.getPrompt().strip();
        String handed = input == null ? "" : input.strip();
        if (handed.isEmpty()) {
            return instructions;
        }
        if (instructions.isEmpty()) {
            return handed;
        }
        return handed + "\n\n" + instructions;
    }

    /**
     * The directory a node's session runs in: a scratch directory of its own, per run,
     * outside this repo.
     *
     * <p>{@code worker.working-directory} points the board's task worker at the repo,
     * because a board task may legitimately be about improving this app. A workflow node
     * is not — it transforms text — and running it in the repo let every node drop files
     * into the working tree (and pick up the repo's {@code CLAUDE.md} as uninvited
     * context). Under {@code java.io.tmpdir} it can still write scratch files if it wants
     * to; nothing it writes lands in the project.
     */
    private static File workDirFor(WorkflowExecution execution) {
        return new File(System.getProperty("java.io.tmpdir"),
                "kanban-workflow/run-" + execution.getId());
    }
}
