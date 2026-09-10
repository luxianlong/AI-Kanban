package com.kanban.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the API remote workers call.
 *
 * <p>The default is <em>off</em>, and that is the important part. These endpoints are exempt
 * from the site password (the gate in {@code frontend/vite.config.js} lets them through the
 * way it lets {@code /api/public/} through), so on a public domain the token below is the only
 * thing standing between a stranger and the ability to claim a task and write any result they
 * like onto the board. An unset token therefore does not mean "no password" — it means the
 * whole agent API answers 503 and no work is handed out at all.
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /**
     * Shared secret every worker sends as {@code Authorization: Bearer <token>}. Set it from
     * the environment (see {@code KANBAN_WORKER_TOKEN} in scripts/env.sh) — never in a file
     * that gets committed. Blank disables the API.
     */
    private String token = "";

    /**
     * How long a claim is good for before {@link LeaseReaper} takes the work back. Wants to be
     * comfortably longer than the worker's heartbeat interval: a worker on a laptop lid or a
     * flaky connection should be able to miss a couple of beats without losing its work
     * halfway through a session it is still paying for.
     */
    private long leaseSeconds = 120;

    /**
     * Name of the repository this server's own checkout is. A task about improving the Kanban
     * itself is only offered to a worker declaring {@code repo:<this name>}, because such a
     * task edits the working tree and runs the guarded deploy — it cannot mean anything on a
     * box that does not have the repo.
     */
    private String repoName = "Dashboard";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /** Whether the agent API is usable at all — i.e. whether a token was configured. */
    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }
}
