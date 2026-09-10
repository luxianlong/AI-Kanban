package com.kanban.protocol;

import java.util.List;

/**
 * One job, handed to one worker, complete enough to run without asking anything further.
 *
 * <p>Notice what is <em>not</em> here: no todo, no node, no execution, no story. The server
 * resolves all of that while it builds the unit — assembling story context, gathering what
 * upstream nodes produced, choosing the effort level — and what crosses the wire is the
 * finished prompt. A worker cannot reach the database and does not need a model of it.
 *
 * <p>{@code refId} is the id of the task or node run this came from. The worker treats it as
 * opaque; it is here so logs on both sides can be lined up when something goes wrong.
 *
 * @param sessionArgs extra CLI arguments for this session — the read-fence settings blob and
 *                    the {@code --effort} flag. Computed by the server so the worker applies
 *                    them without needing to know why they differ between units.
 * @param leaseSeconds how long this claim is good for. The worker must heartbeat within it or
 *                     the server takes the work back and gives it to someone else.
 */
public record WorkUnit(
        String leaseId,
        WorkKind kind,
        long refId,
        String title,
        String prompt,
        List<String> sessionArgs,
        SessionLimitsDto limits,
        WorkDirMode workDirMode,
        String workDirKey,
        List<AttachmentRef> attachments,
        long leaseSeconds) {
}
