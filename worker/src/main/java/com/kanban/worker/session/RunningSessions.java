package com.kanban.worker.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live CLI processes this worker has running, so one can be killed on demand.
 *
 * <p>The server used to hold this, because the server was also the thing running sessions.
 * It now belongs to whoever owns the process — and the request to kill one arrives from the
 * server as a flag on a heartbeat response rather than as a method call, because a worker
 * behind NAT cannot be dialled.
 */
public class RunningSessions {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public void register(String key, Process process) {
        processes.put(key, process);
    }

    public void unregister(String key) {
        processes.remove(key);
    }

    /** Kill one session. Returns false when there was nothing running under that key. */
    public boolean stop(String key) {
        Process process = processes.get(key);
        if (process == null) {
            return false;
        }
        process.destroyForcibly();
        return true;
    }

    /** Kill everything — used on the way down, so a shut-down worker leaves nothing behind. */
    public int stopAll() {
        int stopped = 0;
        for (String key : Map.copyOf(processes).keySet()) {
            if (stop(key)) {
                stopped++;
            }
        }
        return stopped;
    }

    public int size() {
        return processes.size();
    }
}
