package com.kanban.agent;

import com.kanban.protocol.AgentProtocol;
import com.kanban.protocol.AttachmentRef;
import com.kanban.protocol.Capabilities;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.HeartbeatRequest;
import com.kanban.protocol.HeartbeatResponse;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.SessionLimitsDto;
import com.kanban.protocol.WorkDirMode;
import com.kanban.protocol.WorkKind;
import com.kanban.protocol.WorkUnit;
import com.kanban.attachment.Attachment;
import com.kanban.attachment.AttachmentService;
import com.kanban.todo.Todo;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.kanban.work.NodeOutcomeService;
import com.kanban.work.NodeSessionSpec;
import com.kanban.work.NodeSessionPlanner;
import com.kanban.work.SessionOutcome;
import com.kanban.work.TaskEligibility;
import com.kanban.work.TaskOutcomeService;
import com.kanban.work.TaskPromptBuilder;
import com.kanban.work.WorkflowGraphProgress;
import com.kanban.work.WorkflowWorkSource;
import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.RunState;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The server side of the agent protocol: hand work out, keep track of who holds it, and
 * record what comes back.
 *
 * <p><strong>Claiming is atomic in the database</strong>, via
 * {@link TodoRepository#claimIfPending}. The in-process runner never needed anything here — a
 * single scheduler thread picked the work, so "two workers took the same task" could not
 * happen, and {@code WorkflowRunnerService} still relies on that for its own nodes. HTTP
 * claims arrive on whatever Tomcat threads are free, so the guarantee has to be made
 * deliberately.
 *
 * <p>A Java lock is <em>not</em> enough, which is worth recording because it looks like it
 * should be. Wrapping the read-and-save in {@code synchronized} leaves the transaction
 * boundary outside the lock: a second thread enters as soon as the first releases, which is
 * before the first has committed, and still reads the task as PENDING. Both then claim it.
 * That was the first implementation here and AgentConcurrentClaimTest caught it — the cost is
 * two billed Claude sessions doing one job and racing to write conflicting results onto one
 * card. A conditional UPDATE makes the check and the write a single operation, and has the
 * side benefit of staying correct if this server is ever run as more than one process.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TodoRepository todos;
    private final LeaseRepository leases;
    private final TaskEligibility eligibility;
    private final TaskPromptBuilder prompts;
    private final TaskOutcomeService outcomes;
    private final AttachmentService attachments;
    private final LiveLogRegistry liveLog;
    private final WorkerProperties workerProps;
    private final AgentProperties props;
    private final WorkflowWorkSource workflowWork;
    private final WorkflowGraphProgress graph;
    private final NodeSessionPlanner nodePlanner;
    private final NodeOutcomeService nodeOutcomes;
    private final NodeExecutionRepository nodeRuns;
    private final WorkflowExecutionRepository executions;
    private final SecureRandom random = new SecureRandom();

    public AgentService(TodoRepository todos, LeaseRepository leases, TaskEligibility eligibility,
                        TaskPromptBuilder prompts, TaskOutcomeService outcomes,
                        AttachmentService attachments, LiveLogRegistry liveLog,
                        WorkerProperties workerProps, AgentProperties props,
                        WorkflowWorkSource workflowWork, WorkflowGraphProgress graph,
                        NodeSessionPlanner nodePlanner, NodeOutcomeService nodeOutcomes,
                        NodeExecutionRepository nodeRuns, WorkflowExecutionRepository executions) {
        this.todos = todos;
        this.leases = leases;
        this.eligibility = eligibility;
        this.prompts = prompts;
        this.outcomes = outcomes;
        this.attachments = attachments;
        this.liveLog = liveLog;
        this.workerProps = workerProps;
        this.props = props;
        this.workflowWork = workflowWork;
        this.graph = graph;
        this.nodePlanner = nodePlanner;
        this.nodeOutcomes = nodeOutcomes;
        this.nodeRuns = nodeRuns;
        this.executions = executions;
    }

    // --- claim -----------------------------------------------------------------

    /**
     * Hand this worker one unit of work, or nothing if there is none it can run.
     *
     * <p>"None it can run" is not the same as "none at all": a task about improving this
     * project needs the repo, so a worker that did not declare {@code repo:<name>} is told
     * there is nothing for it even while such a task sits at the top of the queue. That is the
     * point of capabilities — the work waits for a worker that can actually do it.
     */
    public Optional<WorkUnit> claim(ClaimRequest request) {
        requireCurrentProtocol(request);
        String worker = workerName(request);
        Set<String> capabilities = Set.copyOf(
                request.capabilities() == null ? List.of() : request.capabilities());

        // Walk the queue oldest-first and try to take each candidate atomically. A lost race
        // is not an error — it means another worker got there first — so we simply move on to
        // the next candidate rather than giving up, which is what lets several workers drain
        // a queue together instead of one winner per poll.
        for (Todo candidate : todos.findByStatusOrderByCreatedAtAsc(TodoStatus.PENDING)) {
            if (!eligibility.isEligible(candidate)
                    || !capabilities.contains(requiredCapability(candidate))) {
                continue;
            }
            if (todos.claimIfPending(candidate.getId(), Instant.now()) == 0) {
                continue;
            }
            // Re-read: the conditional update bypassed the persistence context, so the copy
            // above still says PENDING with the old attempt count.
            Todo todo = todos.findById(candidate.getId()).orElse(null);
            if (todo == null) {
                continue;
            }
            return Optional.of(handOut(todo, worker));
        }
        // Board tasks first, then workflow nodes. Tasks are what a person is waiting on; a
        // workflow run is already machinery, and a node that waits one poll costs nobody's
        // attention.
        if (capabilities.contains(Capabilities.WORKFLOW)) {
            return workflowWork.claimNext().map(claimed -> handOutNode(claimed, worker));
        }
        return Optional.empty();
    }

    /** Record who has this node, open its transcript, and build the unit to send. */
    private WorkUnit handOutNode(WorkflowWorkSource.ClaimedNode claimed, String worker) {
        NodeExecution run = claimed.run();
        NodeSessionSpec spec = nodePlanner.planFor(run.getEffort());
        Instant now = Instant.now();
        Lease lease = new Lease(newLeaseId(), worker, WorkKind.WORKFLOW_NODE, run.getId(), now,
                now.plusSeconds(props.getLeaseSeconds()),
                spec.limits().timeoutSeconds(), spec.limits().backgroundWaitSeconds());
        leases.save(lease);

        String key = LiveLogRegistry.nodeKey(run.getId());
        liveLog.start(key);
        liveLog.append(key, "● Node \"" + run.getNodeName() + "\" ("
                + (run.getOrderIndex() + 1) + "/" + workflowWork.totalNodes(run.getExecutionId()) + ")"
                + (run.getAttempt() > 1 ? ", attempt " + run.getAttempt() + "," : "")
                + " started on worker '" + worker + "'.");
        if (run.getEffort() != null) {
            // Worth saying, because it is the one place two runs of the same prompt differ in
            // what they cost and how long they take.
            liveLog.append(key, "● Effort: " + run.getEffort().cliValue() + ".");
        }
        if (spec.limits().timeoutSeconds() != workerProps.getTimeoutSeconds()) {
            // Only when it differs from every other step's. Saying "this one has longer" is
            // worth a line precisely because a step that sits there for an hour looks stuck.
            liveLog.append(key, "● This step has " + spec.limits().timeoutSeconds()
                    + "s rather than the usual " + workerProps.getTimeoutSeconds()
                    + "s, and may wait " + spec.limits().backgroundWaitSeconds()
                    + "s for work it starts in the background.");
        }
        log.info("Worker '{}' claimed workflow node \"{}\" of execution #{} (lease {}).",
                worker, run.getNodeName(), run.getExecutionId(), lease.getId());

        return new WorkUnit(
                lease.getId(),
                WorkKind.WORKFLOW_NODE,
                run.getId(),
                run.getNodeName(),
                graph.buildPrompt(run, claimed.input()),
                spec.sessionArgs(),
                new SessionLimitsDto(lease.getTimeoutSeconds(), lease.getBackgroundWaitSeconds()),
                // Never the repo. A node transforms text and has no business writing into
                // anyone's working tree — and its own directory is what makes the read fence in
                // sessionArgs mean anything per step, rather than around the whole graph.
                WorkDirMode.SCRATCH,
                NodeSessionPlanner.workDirKeyFor(run.getExecutionId(), run.getId()),
                List.of(),
                props.getLeaseSeconds());
    }

    /** Record who has the work, open its transcript, and build the unit to send. */
    private WorkUnit handOut(Todo todo, String worker) {
        Instant now = Instant.now();
        Lease lease = new Lease(newLeaseId(), worker, WorkKind.TASK, todo.getId(), now,
                now.plusSeconds(props.getLeaseSeconds()),
                workerProps.getTimeoutSeconds(), workerProps.getBackgroundWaitSeconds());
        leases.save(lease);

        // Start the card's transcript here rather than on the worker's first heartbeat, so a
        // card that has been picked up never looks idle even if the worker is slow to check in
        // or dies before it ever does.
        liveLog.start(todo.getId());
        liveLog.append(todo.getId(),
                "● Picked up todo #" + todo.getId() + ": " + todo.getTitle()
                        + "  (worker: " + worker + ")");
        log.info("Worker '{}' claimed todo #{} (lease {}).", worker, todo.getId(), lease.getId());
        return unitFor(lease, todo);
    }

    /**
     * What a worker must declare to be offered this task.
     *
     * <p>A project-improvement task edits this repo's working tree and runs its guarded
     * deploy, so it can only run where that repo is. Everything else is portable.
     */
    String requiredCapability(Todo todo) {
        return todo.isProjectImprovement()
                ? Capabilities.repo(props.getRepoName())
                : Capabilities.GENERAL;
    }

    private WorkUnit unitFor(Lease lease, Todo todo) {
        List<AttachmentRef> files = new ArrayList<>();
        for (Attachment a : attachments.listFor(todo.getId())) {
            files.add(new AttachmentRef(a.getId(), a.getFileName(),
                    AgentProtocol.BASE_PATH + "/attachments/" + a.getId()));
        }
        return new WorkUnit(
                lease.getId(),
                WorkKind.TASK,
                todo.getId(),
                todo.getTitle(),
                prompts.buildPrompt(todo),
                // A board task runs with the worker's ordinary flags; the fence and the
                // --effort flag belong to workflow nodes, which arrive in a later phase.
                List.of(),
                new SessionLimitsDto(lease.getTimeoutSeconds(), lease.getBackgroundWaitSeconds()),
                // A task may legitimately be about improving the project it runs in, so unlike
                // a workflow node it gets the repo rather than a scratch directory.
                todo.isProjectImprovement() ? WorkDirMode.REPO : WorkDirMode.SCRATCH,
                "todo-" + todo.getId(),
                files,
                props.getLeaseSeconds());
    }

    // --- heartbeat -------------------------------------------------------------

    /**
     * Keep a claim alive and move the card's live log forward.
     *
     * <p>Also the server's only way to say anything to a worker: a worker may be behind NAT,
     * so Stop cannot be pushed to it and travels back in this response instead.
     */
    @Transactional
    public HeartbeatResponse heartbeat(String leaseId, HeartbeatRequest request) {
        Lease lease = leases.findById(leaseId).orElse(null);
        if (lease == null || lease.getState() != LeaseState.ACTIVE) {
            // Expired and reaped, or already reported. Tell the worker to give up rather than
            // let it keep paying for work whose result nobody will accept.
            return new HeartbeatResponse(false, 0L, false);
        }

        long seq = request == null ? 0 : request.seq();
        if (seq > lease.getLastSeq()) {
            lease.setLastSeq(seq);
            List<String> lines = request.logLines();
            if (lines != null && !lines.isEmpty() && lease.getKind() == WorkKind.TASK) {
                for (String line : lines) {
                    liveLog.append(lease.getRefId(), line);
                }
            }
        }
        // A repeated or older sequence still renews the lease — the worker is plainly alive —
        // but its lines are dropped, so a retry after a network wobble cannot double-append.
        lease.setExpiresAt(Instant.now().plusSeconds(props.getLeaseSeconds()));
        leases.save(lease);
        return new HeartbeatResponse(lease.isStopRequested(),
                lease.getExpiresAt().toEpochMilli(), true);
    }

    // --- result ----------------------------------------------------------------

    /**
     * Record what a session produced. The verdict is the server's, not the worker's — see
     * {@link TaskOutcomeService} and {@link NodeOutcomeService}.
     */
    @Transactional
    public void report(String leaseId, ResultRequest request) {
        Lease lease = activeLease(leaseId);
        SessionOutcome outcome = new SessionOutcome(request.exitCode(), request.timedOut(),
                request.abandonedBackgroundWork(), request.stopped(), request.finalOutput(),
                request.costUsd(), request.durationMs());

        if (lease.getKind() == WorkKind.WORKFLOW_NODE) {
            reportNode(lease, outcome);
        } else {
            reportTask(lease, outcome);
        }
        lease.setState(LeaseState.DONE);
        leases.save(lease);
    }

    private void reportTask(Lease lease, SessionOutcome outcome) {
        Todo todo = todos.findById(lease.getRefId()).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.GONE, "Task #" + lease.getRefId() + " no longer exists."));
        TaskOutcomeService.Applied applied = outcomes.apply(todo, outcome,
                lease.getTimeoutSeconds(), lease.getBackgroundWaitSeconds());
        liveLog.append(todo.getId(), applied.logLine());
        log.info("Worker '{}' reported todo #{} as {}.", lease.getWorkerName(), todo.getId(),
                applied.status());
    }

    private void reportNode(Lease lease, SessionOutcome outcome) {
        NodeExecution run = nodeRuns.findById(lease.getRefId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.GONE,
                        "Node run #" + lease.getRefId() + " no longer exists."));
        NodeOutcomeService.Applied applied = nodeOutcomes.apply(run, outcome,
                lease.getTimeoutSeconds(), lease.getBackgroundWaitSeconds());
        liveLog.append(LiveLogRegistry.nodeKey(run.getId()), applied.logLine());
        log.info("Worker '{}' reported node \"{}\" of execution #{} as {}.",
                lease.getWorkerName(), run.getNodeName(), run.getExecutionId(), applied.status());

        // Advance the graph here, because nothing else will: with the session on another
        // machine there is no lane sitting inside the run to notice it has finished, or that a
        // failure has just poisoned every branch below it.
        executions.findById(run.getExecutionId()).ifPresent(graph::finishIfSettled);
    }

    /**
     * A worker handing work back on its way down — the polite version of vanishing.
     *
     * <p>What "back" means differs by kind, and follows the policy each already had for work a
     * restart interrupted. A task returns to the queue immediately; the attempt it spent still
     * counts, so work that keeps being handed back is capped by the same max-attempts rule as
     * work that keeps being orphaned. A node is failed instead of re-queued, because it may
     * already have done part of its work and re-running it would silently duplicate that
     * halfway through a graph — the run is then in exactly the state Retry is for.
     */
    @Transactional
    public void release(String leaseId) {
        Lease lease = activeLease(leaseId);
        lease.setState(LeaseState.RELEASED);
        leases.save(lease);
        if (lease.getKind() == WorkKind.WORKFLOW_NODE) {
            releaseNode(lease);
        } else {
            releaseTask(lease);
        }
        log.info("Worker '{}' released {} #{}.", lease.getWorkerName(), lease.getKind(),
                lease.getRefId());
    }

    private void releaseTask(Lease lease) {
        todos.findById(lease.getRefId()).ifPresent(todo -> {
            if (todo.getStatus() == TodoStatus.IN_PROGRESS) {
                todo.setStatus(TodoStatus.PENDING);
                todos.save(todo);
                liveLog.append(todo.getId(),
                        "■ Worker '" + lease.getWorkerName() + "' handed this back; requeued.");
            }
        });
    }

    private void releaseNode(Lease lease) {
        nodeRuns.findById(lease.getRefId()).ifPresent(run -> {
            if (run.getStatus() != RunState.RUNNING) {
                return;
            }
            run.setStatus(RunState.FAILED);
            run.setError("Worker '" + lease.getWorkerName() + "' shut down while running this "
                    + "step. Not re-run automatically, because the step may already have done "
                    + "part of its work — retry the run if you decide another attempt is safe.");
            run.setFinishedAt(Instant.now());
            nodeRuns.save(run);
            liveLog.append(LiveLogRegistry.nodeKey(run.getId()),
                    "■ Worker '" + lease.getWorkerName() + "' handed this step back.");
            executions.findById(run.getExecutionId()).ifPresent(graph::finishIfSettled);
        });
    }

    /**
     * Ask the worker holding this unit to stop. Takes effect on its next heartbeat, which is
     * as prompt as it can be for a worker the server cannot dial.
     */
    @Transactional
    public boolean requestStop(WorkKind kind, Long refId) {
        return leases.findByKindAndRefIdAndState(kind, refId, LeaseState.ACTIVE)
                .map(lease -> {
                    lease.setStopRequested(true);
                    leases.save(lease);
                    return true;
                })
                .orElse(false);
    }

    // --- helpers ---------------------------------------------------------------

    private Lease activeLease(String leaseId) {
        Lease lease = leases.findById(leaseId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No such lease."));
        if (lease.getState() != LeaseState.ACTIVE) {
            // Most often: the worker went quiet, the reaper took the work back, and the worker
            // has now surfaced with an answer to a question somebody else may already be
            // working on. Refusing is the only safe reading.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lease " + leaseId + " is " + lease.getState() + " and can no longer be reported on.");
        }
        return lease;
    }

    private void requireCurrentProtocol(ClaimRequest request) {
        if (request.protocolVersion() != AgentProtocol.VERSION) {
            // A deploy rebuilds and restarts this server but cannot reach a worker jar on
            // someone else's machine, so a version mismatch is expected eventually and has to
            // say what to do about it.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This worker speaks protocol v" + request.protocolVersion() + " but the server "
                            + "speaks v" + AgentProtocol.VERSION + ". Update the worker jar.");
        }
    }

    private String workerName(ClaimRequest request) {
        String name = request.worker();
        return name == null || name.isBlank() ? "anonymous-worker" : name.trim();
    }

    private String newLeaseId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
