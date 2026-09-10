package com.kanban.protocol;

/**
 * The vocabulary of worker capabilities.
 *
 * <p>A worker announces what it can do; the server only offers a unit whose required
 * capability the worker declared. This exists because one kind of work is genuinely not
 * portable: a task about improving the Kanban itself edits the repo and runs its guarded
 * deploy, so it can only run on a box that has that repo checked out. Everything else —
 * general tasks, workflow nodes — runs anywhere.
 *
 * <p>Capabilities are plain strings on the wire so a worker can declare one this class has
 * never heard of; the server matches by equality and nothing more.
 */
public final class Capabilities {

    /** Ordinary board tasks: do the work, reply with the result. Portable. */
    public static final String GENERAL = "general";

    /** Workflow nodes: transform the text handed across an edge. Portable. */
    public static final String WORKFLOW = "workflow";

    /** Prefix for "I have this repository checked out and may build it" — {@code repo:Dashboard}. */
    public static final String REPO_PREFIX = "repo:";

    /** The capability a unit needs to run against the named repository. */
    public static String repo(String name) {
        return REPO_PREFIX + name;
    }

    private Capabilities() {
    }
}
