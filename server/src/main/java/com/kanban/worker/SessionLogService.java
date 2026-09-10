package com.kanban.worker;

import com.kanban.todo.Todo;
import com.kanban.workflow.NodeExecution;
import org.springframework.stereotype.Service;

/**
 * The one place that knows a session transcript has two homes.
 *
 * <p>{@link LiveLogRegistry} holds it while the session runs: in memory, keyed by session,
 * polled every 1.5s by whoever is watching. That is the right store for a transcript being
 * watched and the wrong one for a transcript being <em>consulted</em> — it is lost at the
 * next restart, and this app restarts on every deploy, so "what did that task do?" had no
 * answer for anything older than the last few hours. The durable half is a column on the
 * row itself ({@code Todo.sessionLog} / {@code NodeExecution.sessionLog}).
 *
 * <p>Three rules keep the two halves from contradicting each other:
 *
 * <ul>
 *   <li><b>{@link #keep} at the end, not along the way.</b> Archiving on every heartbeat
 *       would rewrite a growing CLOB several times a minute per running session, to store
 *       something nobody can read yet. The transcript is copied once, when the work settles
 *       — reported, handed back, or reaped.</li>
 *   <li><b>A row shows the attempt it is currently reporting on, or nothing.</b> The kept
 *       transcript is dropped when the row is handed out again ({@link #forget}, and
 *       {@code NodeExecution.resetForRetry} for a step), so the previous attempt's session
 *       can never be served as the running one's.</li>
 *   <li><b>{@link #read} prefers memory when a session is <em>open</em> on that key</b>,
 *       not merely when memory is non-empty. Those two questions have the same answer
 *       everywhere today, because the rule above leaves nothing in the column for a
 *       non-empty check to fall through to — no test distinguishes them, and none should
 *       pretend to. The narrower question is asked anyway because it is the one this class
 *       can answer on its own: "show the live session" stays right here even if some future
 *       caller forgets to clear the column.</li>
 * </ul>
 */
@Service
public class SessionLogService {

    private final LiveLogRegistry liveLog;

    public SessionLogService(LiveLogRegistry liveLog) {
        this.liveLog = liveLog;
    }

    /** Copy a finished task's transcript onto its row. The caller saves (or is in a transaction). */
    public void keep(Todo todo) {
        todo.setSessionLog(emptyToNull(liveLog.get(todo.getId())));
    }

    /** Copy a finished step's transcript onto its row. The caller saves (or is in a transaction). */
    public void keep(NodeExecution run) {
        run.setSessionLog(emptyToNull(liveLog.get(LiveLogRegistry.nodeKey(run.getId()))));
    }

    /** The transcript to show for this task: the live one if a session is open, else the kept one. */
    public String read(Todo todo) {
        return liveLog.has(todo.getId())
                ? liveLog.get(todo.getId())
                : nullToEmpty(todo.getSessionLog());
    }

    /** The transcript to show for this step: the live one if a session is open, else the kept one. */
    public String read(NodeExecution run) {
        String key = LiveLogRegistry.nodeKey(run.getId());
        return liveLog.has(key) ? liveLog.get(key) : nullToEmpty(run.getSessionLog());
    }

    /**
     * Whether what {@link #read} returns came from the column rather than a live session —
     * so the UI can say "saved transcript" instead of implying something is still streaming.
     */
    public boolean isArchived(Todo todo) {
        return !liveLog.has(todo.getId()) && !nullToEmpty(todo.getSessionLog()).isEmpty();
    }

    /** As {@link #isArchived(Todo)}, for one workflow step's run. */
    public boolean isArchived(NodeExecution run) {
        return !liveLog.has(LiveLogRegistry.nodeKey(run.getId()))
                && !nullToEmpty(run.getSessionLog()).isEmpty();
    }

    /**
     * Forget the kept transcript because a new session is about to open on this row.
     *
     * <p>A card shows the transcript of the attempt it is currently reporting on. Without
     * this, a restart during attempt two would fall back to attempt one's transcript and
     * present it as the running session's — the one reading in which stale text is worse
     * than none. (A workflow step gets the same treatment from
     * {@code NodeExecution.resetForRetry}, which has a whole attempt's fields to clear.)
     */
    public void forget(Todo todo) {
        todo.setSessionLog(null);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
