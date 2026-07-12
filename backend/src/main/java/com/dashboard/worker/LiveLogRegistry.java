package com.dashboard.worker;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of the live transcript for each todo while (and after) a
 * Claude Code session runs on it. The worker appends lines as the session
 * streams output; the API reads the current text so the UI can show progress
 * on an in-progress item.
 *
 * <p>Transcripts live only in memory (lost on restart) — the durable final
 * answer is persisted on the todo's {@code result} field. Each transcript is
 * capped so a chatty session can't exhaust the heap.
 */
@Component
public class LiveLogRegistry {

    private static final int MAX_CHARS = 200_000;

    private final Map<Long, StringBuilder> logs = new ConcurrentHashMap<>();

    /** Begin a fresh transcript for the given todo, discarding any prior one. */
    public void start(Long todoId) {
        logs.put(todoId, new StringBuilder());
    }

    /** Append one line (a newline is added automatically). Thread-safe per todo. */
    public void append(Long todoId, String line) {
        StringBuilder sb = logs.computeIfAbsent(todoId, k -> new StringBuilder());
        synchronized (sb) {
            sb.append(line).append('\n');
            if (sb.length() > MAX_CHARS) {
                // Keep the tail — recent activity is what a watcher cares about.
                sb.delete(0, sb.length() - MAX_CHARS);
            }
        }
    }

    /** Current transcript text, or empty string if none. */
    public String get(Long todoId) {
        StringBuilder sb = logs.get(todoId);
        if (sb == null) {
            return "";
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    public boolean has(Long todoId) {
        return logs.containsKey(todoId);
    }

    public void clear(Long todoId) {
        logs.remove(todoId);
    }
}
