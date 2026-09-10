package com.kanban.worker;

import com.kanban.protocol.AttachmentRef;
import com.kanban.protocol.HeartbeatRequest;
import com.kanban.protocol.HeartbeatResponse;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.WorkDirMode;
import com.kanban.protocol.WorkUnit;
import com.kanban.worker.session.ClaudeSessionRunner;
import com.kanban.worker.session.ClaudeSessionRunner.ProcessResult;
import com.kanban.worker.session.RunningSessions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Claim one unit of work, run it, report what happened. Repeat.
 *
 * <p>Strictly one at a time. A worker that wanted more concurrency would be a second worker
 * process, which is now a thing you can simply start — on this machine or another — and is a
 * far better answer than a thread pool here, because it needs no coordination at all: the
 * server hands each claim out exactly once.
 *
 * <p>The session runs on its own thread while this one heartbeats, because both have to happen
 * at once and the session may last an hour. The heartbeat is doing three jobs: it renews the
 * lease (stop, and the server takes the work back), it carries the live log up, and it is the
 * only way a Stop from the board can reach a process the server cannot address.
 */
@Component
public class WorkLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkLoop.class);

    private final AgentClient client;
    private final WorkerSettings settings;
    private final RunningSessions sessions = new RunningSessions();
    private final ReentrantLock tickLock = new ReentrantLock();
    private final ExecutorService sessionThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "claude-session");
                t.setDaemon(true);
                return t;
            });

    /** The lease currently held, so shutdown can hand it back rather than let it expire. */
    private final AtomicReference<String> heldLease = new AtomicReference<>();

    private volatile boolean shuttingDown;

    public WorkLoop(AgentClient client, WorkerSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        int killed = sessions.stopAll();
        String lease = heldLease.get();
        if (lease != null) {
            // Hand the work back explicitly instead of vanishing. The server would recover it
            // when the lease expired anyway, but that is a minute of a card sitting there
            // looking busy for no reason.
            client.release(lease);
        }
        if (killed > 0) {
            log.info("Shutting down: killed {} running session(s) and released the lease.", killed);
        }
        sessionThread.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:5000}",
            initialDelayString = "${worker.initial-delay-ms:2000}")
    public void poll() {
        if (shuttingDown || !tickLock.tryLock()) {
            return;
        }
        try {
            Optional<WorkUnit> claimed = client.claim();
            if (claimed.isEmpty()) {
                return;
            }
            WorkUnit unit = claimed.get();
            log.info("Claimed {} #{}: {}", unit.kind(), unit.refId(), unit.title());
            runAndReport(unit);
        } catch (AgentClient.WorkerRejected e) {
            // Not transient — a wrong token or a stale jar. Say it once per poll rather than
            // spinning silently, since nothing this worker does will fix it.
            log.error("{}", e.getMessage());
        } catch (Exception e) {
            log.warn("Poll failed ({}); will try again.", e.toString());
        } finally {
            tickLock.unlock();
        }
    }

    private void runAndReport(WorkUnit unit) {
        heldLease.set(unit.leaseId());
        LogBuffer buffer = new LogBuffer();
        ClaudeSessionRunner runner = new ClaudeSessionRunner(settings, buffer, sessions);
        String key = "unit-" + unit.leaseId();
        File workDir = workDirFor(unit);
        boolean stopped = false;

        try {
            String prompt = materialiseAttachments(unit, workDir, buffer);
            Future<ProcessResult> running = sessionThread.submit(
                    () -> runner.run(key, prompt, workDir, unit.sessionArgs(), unit.limits()));

            long seq = 0;
            while (!running.isDone()) {
                Thread.sleep(settings.getHeartbeatIntervalMs());
                HeartbeatResponse response =
                        beat(unit.leaseId(), ++seq, buffer.drain());
                if (response == null) {
                    continue; // transient failure; the lease has room for a few misses
                }
                if (!response.known()) {
                    // The server has forgotten this lease — it expired and the work was taken
                    // back. Carrying on would burn money on an answer nobody will accept.
                    log.warn("Lease {} is no longer known to the server; abandoning the session.",
                            unit.leaseId());
                    sessions.stop(key);
                    stopped = true;
                    break;
                }
                if (response.stopRequested()) {
                    log.info("Stop requested for {} #{}; terminating the session.",
                            unit.kind(), unit.refId());
                    sessions.stop(key);
                    stopped = true;
                }
            }

            ProcessResult result = running.get();
            // One last flush, so the tail of the transcript is not lost between the final
            // heartbeat and the report.
            beat(unit.leaseId(), ++seq, buffer.drain());
            client.report(unit.leaseId(), new ResultRequest(
                    result.exitCode(), result.timedOut(), result.abandonedBackgroundWork(),
                    stopped, result.finalOutput(), result.costUsd(), result.durationMs()));
            log.info("Reported {} #{} (exit {}{}).", unit.kind(), unit.refId(), result.exitCode(),
                    stopped ? ", stopped" : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Running {} #{} failed", unit.kind(), unit.refId(), e);
            reportWorkerError(unit, e);
        } finally {
            heldLease.set(null);
        }
    }

    /**
     * A failure in the worker itself, rather than in the session it was running. Reported as a
     * non-zero exit so the board records it as a failure with the reason attached, rather than
     * leaving the card busy until the lease runs out.
     */
    private void reportWorkerError(WorkUnit unit, Exception e) {
        if (shuttingDown) {
            return; // shutdown releases the lease instead
        }
        try {
            client.report(unit.leaseId(), new ResultRequest(-1, false, false, false,
                    "The worker failed while running this: " + e, null, null));
        } catch (Exception ignored) {
            // Nothing left to try; the lease will expire and the server will recover the work.
        }
    }

    private HeartbeatResponse beat(String leaseId, long seq, List<String> lines) {
        try {
            return client.heartbeat(leaseId, new HeartbeatRequest(seq, lines));
        } catch (Exception e) {
            // A missed heartbeat is survivable — the lease is deliberately several intervals
            // long — so keep the session running and try again on the next beat. The lines in
            // this batch are lost, which is why they are drained only after a successful call
            // would have taken them.
            log.debug("Heartbeat {} failed: {}", seq, e.toString());
            return null;
        }
    }

    /**
     * Where this unit's session runs.
     *
     * <p>The server chooses which of the two it is, because the reason is the server's: a board
     * task may legitimately be about improving the project it runs in, while a workflow node
     * only transforms text and gets a directory of its own — which is also what makes the read
     * fence in its session args mean anything per step.
     */
    private File workDirFor(WorkUnit unit) {
        if (unit.workDirMode() == WorkDirMode.REPO) {
            return new File(settings.getRepoDirectory());
        }
        return new File(settings.getScratchRoot(), unit.workDirKey());
    }

    /**
     * Fetch any files the user attached and point the prompt at where they landed.
     *
     * <p>The server cannot name these: it knows them as paths beside its own database, which
     * mean nothing here. So it sends URLs and a placeholder, and the real local paths are
     * substituted once the files exist on this machine.
     */
    private String materialiseAttachments(WorkUnit unit, File workDir, LogBuffer buffer)
            throws Exception {
        List<AttachmentRef> files = unit.attachments();
        if (files == null || files.isEmpty()) {
            return unit.prompt();
        }
        Path dir = workDir.toPath().resolve("attachments");
        List<String> lines = new ArrayList<>();
        for (AttachmentRef file : files) {
            Path target = dir.resolve(file.id() + suffixOf(file.fileName()));
            client.download(file.downloadUrl(), target);
            lines.add("  - " + target.toAbsolutePath()
                    + "  (original name: " + file.fileName() + ")");
        }
        buffer.append("", "● Downloaded " + files.size() + " attached file(s).");
        return unit.prompt().replace(com.kanban.protocol.AttachmentsPlaceholder.TOKEN, String.join("\n", lines));
    }

    private String suffixOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }
}
