package com.kanban.worker;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the live Claude Code {@link Process} backing each in-progress todo so a
 * user can stop a running session on demand.
 *
 * <p>The worker {@link #register}s a process the moment it starts and
 * {@link #unregister}s it when the run ends, so at any instant this holds exactly
 * the sessions that are actually running. The API calls {@link #stop} to forcibly
 * terminate one; the worker then reads {@link #consumeStopRequested} to record the
 * run as user-stopped rather than a generic crash.
 *
 * <p>Like {@link LiveLogRegistry}, this lives only in memory — a backend restart
 * ends every session anyway, and orphaned in-progress todos are recovered by
 * {@link ClaudeWorkerService#requeueOrphans()}.
 */
@Component
public class RunningSessionRegistry {

    private final Map<Long, Process> processes = new ConcurrentHashMap<>();
    private final Set<Long> stopRequested = ConcurrentHashMap.newKeySet();

    /** Record the live process for a todo that is starting to run. */
    void register(Long todoId, Process process) {
        processes.put(todoId, process);
    }

    /** Drop a todo's process once its run has finished (for any reason). */
    void unregister(Long todoId) {
        processes.remove(todoId);
    }

    /** True while a Claude session is actively running for this todo. */
    public boolean isRunning(Long todoId) {
        return processes.containsKey(todoId);
    }

    /**
     * Request that the running session for this todo be stopped: flag the run as
     * user-stopped (so the worker records it as such) and forcibly terminate the
     * process. Returns {@code false} if no session is currently running for the
     * todo, so the caller can report that there was nothing to stop.
     */
    public boolean stop(Long todoId) {
        Process process = processes.get(todoId);
        if (process == null) {
            return false;
        }
        // Flag before killing so the worker thread, which may wake the instant the
        // process dies, always sees the stop was deliberate.
        stopRequested.add(todoId);
        process.destroyForcibly();
        return true;
    }

    /**
     * Consume the user-stop flag for a todo: returns {@code true} exactly once if a
     * stop was requested for this run, and clears it. The worker calls this after a
     * run ends to tell a user-requested stop apart from a real error or timeout.
     */
    boolean consumeStopRequested(Long todoId) {
        return stopRequested.remove(todoId);
    }
}
