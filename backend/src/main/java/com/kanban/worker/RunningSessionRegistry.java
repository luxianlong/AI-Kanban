package com.kanban.worker;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the live Claude Code {@link Process} backing each running session so a
 * user can stop one on demand.
 *
 * <p>{@link ClaudeSessionRunner} {@link #register}s a process the moment it starts
 * and {@link #unregister}s it when the run ends, so at any instant this holds
 * exactly the sessions that are actually running. The API calls {@link #stop} to
 * forcibly terminate one; the caller then reads {@link #consumeStopRequested} to
 * record the run as user-stopped rather than a generic crash.
 *
 * <p>Sessions are keyed the same way as {@link LiveLogRegistry}: {@code todo:<id>}
 * for a board task, {@code node:<id>} for a workflow node run. Sharing one registry
 * is what lets {@link #abortAll()} kill <em>every</em> kind of session on shutdown.
 *
 * <p>Like {@link LiveLogRegistry}, this lives only in memory — a backend restart
 * ends every session anyway, and orphaned in-progress work is recovered on the next
 * start (see {@link ClaudeWorkerService#requeueOrphans()}).
 */
@Component
public class RunningSessionRegistry implements ApplicationListener<ContextClosedEvent> {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();

    /**
     * Raised as soon as the Spring context starts closing — which is <em>before</em>
     * any bean's {@code @PreDestroy} runs. Everything that launches sessions reads it
     * to tell "the JVM is going down" apart from "the session crashed", so a run the
     * shutdown killed is left for the next start to recover rather than recorded as a
     * genuine failure. Deliberately event-driven rather than a per-service
     * {@code @PreDestroy} flag: destruction order between two independent services is
     * not guaranteed, so one could abort the other's session before it knew why.
     */
    private volatile boolean shuttingDown = false;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown = true;
    }

    /** True once the context has begun closing (see {@link #onApplicationEvent}). */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** Record the live process for a session that is starting to run. */
    public void register(String key, Process process) {
        processes.put(key, process);
    }

    /** Drop a session's process once its run has finished (for any reason). */
    public void unregister(String key) {
        processes.remove(key);
    }

    /** True while a Claude session is actively running under this key. */
    public boolean isRunning(String key) {
        return processes.containsKey(key);
    }

    /**
     * Request that this session be stopped: flag the run as user-stopped (so the
     * caller records it as such) and forcibly terminate the process. Returns
     * {@code false} if no session is currently running under the key, so the caller
     * can report that there was nothing to stop.
     */
    public boolean stop(String key) {
        Process process = processes.get(key);
        if (process == null) {
            return false;
        }
        // Flag before killing so the worker thread, which may wake the instant the
        // process dies, always sees the stop was deliberate.
        stopRequested.add(key);
        process.destroyForcibly();
        return true;
    }

    /**
     * Consume the user-stop flag for a session: returns {@code true} exactly once if
     * a stop was requested for this run, and clears it. The caller uses this after a
     * run ends to tell a user-requested stop apart from a real error or timeout.
     */
    public boolean consumeStopRequested(String key) {
        return stopRequested.remove(key);
    }

    // --- todo-keyed convenience overloads (the original API) ---

    public void register(Long todoId, Process process) {
        register(LiveLogRegistry.todoKey(todoId), process);
    }

    public void unregister(Long todoId) {
        unregister(LiveLogRegistry.todoKey(todoId));
    }

    public boolean isRunning(Long todoId) {
        return isRunning(LiveLogRegistry.todoKey(todoId));
    }

    public boolean stop(Long todoId) {
        return stop(LiveLogRegistry.todoKey(todoId));
    }

    public boolean consumeStopRequested(Long todoId) {
        return consumeStopRequested(LiveLogRegistry.todoKey(todoId));
    }

    /**
     * Forcibly end every running session because the JVM is shutting down, returning
     * how many were killed.
     *
     * <p>Deliberately does <em>not</em> raise the user-stop flag: nobody asked for these
     * to stop, they were interrupted. The worker records that by leaving the todo
     * IN_PROGRESS so the next start requeues it.
     */
    int abortAll() {
        int aborted = 0;
        for (Process process : processes.values()) {
            process.destroyForcibly();
            aborted++;
        }
        return aborted;
    }
}
