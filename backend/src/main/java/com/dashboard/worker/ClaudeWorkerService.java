package com.dashboard.worker;

import com.dashboard.todo.Todo;
import com.dashboard.todo.TodoRepository;
import com.dashboard.todo.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Periodically drains the {@link TodoStatus#PENDING} queue by launching a
 * headless Claude Code session per item and recording whatever it produced.
 *
 * <p>One item is moved to {@link TodoStatus#IN_PROGRESS} before the session
 * starts, so a crash mid-run leaves a visible, non-repeating trail. The poll
 * uses a {@link ReentrantLock} so slow sessions never cause overlapping ticks.
 */
@Service
@EnableConfigurationProperties(WorkerProperties.class)
public class ClaudeWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWorkerService.class);

    private final TodoRepository repository;
    private final WorkerProperties props;
    private final ReentrantLock tickLock = new ReentrantLock();

    public ClaudeWorkerService(TodoRepository repository, WorkerProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /**
     * Poll for pending work. Interval is {@code worker.poll-interval-ms}
     * (default 15s). Skips the tick entirely if a previous one is still running.
     */
    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:15000}",
            initialDelayString = "${worker.initial-delay-ms:10000}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        if (!tickLock.tryLock()) {
            log.debug("Previous worker tick still running; skipping.");
            return;
        }
        try {
            List<Todo> pending = repository.findByStatusOrderByCreatedAtAsc(TodoStatus.PENDING);
            if (pending.isEmpty()) {
                return;
            }
            int limit = Math.min(props.getBatchSize(), pending.size());
            log.info("Worker picked up {} of {} pending todo(s).", limit, pending.size());
            for (int i = 0; i < limit; i++) {
                process(pending.get(i));
            }
        } finally {
            tickLock.unlock();
        }
    }

    private void process(Todo todo) {
        todo.setStatus(TodoStatus.IN_PROGRESS);
        todo.setProcessedAt(Instant.now());
        repository.save(todo);
        log.info("Resolving todo #{}: {}", todo.getId(), todo.getTitle());

        try {
            ProcessResult r = runClaude(buildPrompt(todo));
            if (r.timedOut()) {
                fail(todo, "Timed out after " + props.getTimeoutSeconds() + "s.\n\n" + r.output());
            } else if (r.exitCode() != 0) {
                fail(todo, "Claude exited with code " + r.exitCode() + ".\n\n" + r.output());
            } else {
                todo.setStatus(TodoStatus.RESOLVED);
                todo.setResult(r.output().isBlank() ? "(no output)" : r.output());
                repository.save(todo);
                log.info("Resolved todo #{}.", todo.getId());
            }
        } catch (Exception e) {
            log.error("Worker error on todo #{}", todo.getId(), e);
            fail(todo, "Worker error: " + e.getMessage());
        }
    }

    private void fail(Todo todo, String message) {
        todo.setStatus(TodoStatus.FAILED);
        todo.setResult(message);
        repository.save(todo);
        log.warn("Failed todo #{}: {}", todo.getId(), message.lines().findFirst().orElse(""));
    }

    /** The instruction handed to the Claude Code session. */
    private String buildPrompt(Todo todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous worker resolving a single todo item from a dashboard.\n");
        sb.append("Do the work needed to complete it, then reply with a short summary of what you did ");
        sb.append("and any result or answer the user needs.\n\n");
        sb.append("TODO TITLE: ").append(todo.getTitle()).append('\n');
        if (todo.getDescription() != null && !todo.getDescription().isBlank()) {
            sb.append("DETAILS:\n").append(todo.getDescription()).append('\n');
        }
        return sb.toString();
    }

    private ProcessResult runClaude(String prompt) throws IOException, InterruptedException {
        File workDir = new File(props.getWorkingDirectory());
        if (!workDir.exists() && !workDir.mkdirs()) {
            log.warn("Could not create working directory {}", workDir);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(props.getClaudeCommand());
        cmd.addAll(props.getExtraArgs());
        cmd.add("-p");
        cmd.add(prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true);

        Process process = pb.start();
        // Print mode takes the prompt as an arg, so we don't need stdin.
        process.getOutputStream().close();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            return new ProcessResult(-1, output, true);
        }
        return new ProcessResult(process.exitValue(), output, false);
    }

    private record ProcessResult(int exitCode, String output, boolean timedOut) {
    }
}
