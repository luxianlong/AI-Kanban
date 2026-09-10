package com.kanban.protocol;

/**
 * Where the worker should run this unit's session.
 *
 * <p>The server decides, because the reason is the server's: a board task may legitimately
 * be about improving the project it runs in, while a workflow node only transforms text and
 * has no business writing into anyone's working tree.
 */
public enum WorkDirMode {

    /**
     * The repository the worker declared a {@code repo:<name>} capability for. Only sent to a
     * worker that declared it — see {@link Capabilities}.
     */
    REPO,

    /**
     * A throwaway directory the worker creates, unique per unit. Paired with the read fence in
     * {@code sessionArgs}, this is what stops a node seeing anything but its own files.
     */
    SCRATCH
}
