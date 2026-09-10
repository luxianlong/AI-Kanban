package com.kanban.worker;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of the live transcript for each Claude Code session while (and
 * after) it runs. The worker appends lines as the session streams output; the API
 * reads the current text so the UI can show progress on something in flight.
 *
 * <p>Transcripts are keyed by an opaque <em>session key</em> so the two things that
 * run sessions can share one registry without colliding: a todo logs under
 * {@code todo:<id>} (see the {@code Long}-keyed convenience methods) and a workflow
 * node run logs under {@code node:<id>} (see {@link #nodeKey}). Both id spaces are
 * database identities that start at 1, so the prefix is what keeps them apart.
 *
 * <p>Transcripts live only in memory (lost on restart) — the durable final answer
 * is persisted on the todo's {@code result} / the node run's {@code output} field.
 * Each transcript is capped so a chatty session can't exhaust the heap.
 */
@Component
public class LiveLogRegistry {

    private static final int MAX_CHARS = 200_000;

    private final Map<String, StringBuilder> logs = new ConcurrentHashMap<>();

    /** Session key for a todo's run. */
    public static String todoKey(Long todoId) {
        return "todo:" + todoId;
    }

    /** Session key for one workflow node's run within an execution. */
    public static String nodeKey(Long nodeExecutionId) {
        return "node:" + nodeExecutionId;
    }

    /** Begin a fresh transcript for the given session, discarding any prior one. */
    public void start(String key) {
        logs.put(key, new StringBuilder());
    }

    /** Append one line (a newline is added automatically). Thread-safe per session. */
    public void append(String key, String line) {
        StringBuilder sb = logs.computeIfAbsent(key, k -> new StringBuilder());
        synchronized (sb) {
            sb.append(line).append('\n');
            if (sb.length() > MAX_CHARS) {
                // Keep the tail — recent activity is what a watcher cares about.
                sb.delete(0, sb.length() - MAX_CHARS);
            }
        }
    }

    /** Current transcript text, or empty string if none. */
    public String get(String key) {
        StringBuilder sb = logs.get(key);
        if (sb == null) {
            return "";
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    public boolean has(String key) {
        return logs.containsKey(key);
    }

    public void clear(String key) {
        logs.remove(key);
    }

    // --- todo-keyed convenience overloads (the original API) ---

    public void start(Long todoId) {
        start(todoKey(todoId));
    }

    public void append(Long todoId, String line) {
        append(todoKey(todoId), line);
    }

    public String get(Long todoId) {
        return get(todoKey(todoId));
    }

    public boolean has(Long todoId) {
        return has(todoKey(todoId));
    }

    public void clear(Long todoId) {
        clear(todoKey(todoId));
    }
}
