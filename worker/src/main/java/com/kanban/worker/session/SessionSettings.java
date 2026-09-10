package com.kanban.worker.session;

import java.util.List;

/**
 * What the runner needs to know to launch the CLI at all — as opposed to what it is told about
 * one particular unit of work, which arrives in the work unit.
 */
public interface SessionSettings {

    /** Path to the Claude Code CLI. An absolute path if it isn't on the service PATH. */
    String claudeCommand();

    /**
     * Args inserted before the prompt for <em>every</em> session. Configuration that applies to
     * one run belongs in the work unit's own session args instead.
     */
    List<String> extraArgs();
}
