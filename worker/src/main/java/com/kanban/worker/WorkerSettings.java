package com.kanban.worker;

import com.kanban.worker.session.SessionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Everything one worker needs to know: which board to serve, how to prove it may, what it is
 * able to run, and how to launch the CLI.
 *
 * <p>Set these in the worker's own {@code application.yml} beside its jar, or as
 * {@code --worker.*} arguments. Nothing here is shared with the server's configuration — that
 * is the point of the split, and it is what lets a second worker on a different machine differ
 * in every one of these without the board knowing.
 */
@Component
@ConfigurationProperties(prefix = "worker")
public class WorkerSettings implements SessionSettings {

    /** Where the board is. Include the scheme; https for anything but localhost. */
    private String serverUrl = "http://localhost:8080";

    /** The shared secret the server checks. Must match its {@code agent.token}. */
    private String token = "";

    /** This worker's name for itself. Shown on the board so a card says where it ran. */
    private String name = defaultName();

    /**
     * What this worker is willing and able to run.
     *
     * <p>{@code general} and {@code workflow} are portable — any box can do them. A
     * {@code repo:<name>} capability is a claim about <em>this machine</em>: that the named
     * repository is checked out at {@link #repoDirectory} and may be edited, built and
     * deployed from here. Declaring one you cannot honour is how a task ends up trying to run
     * a guarded deploy in an empty directory.
     */
    private List<String> capabilities = List.of("general", "workflow");

    /**
     * Working tree for units that need the repo. Only meaningful alongside a {@code repo:}
     * capability; everything else runs in a throwaway directory under {@link #scratchRoot}.
     */
    private String repoDirectory = System.getProperty("user.dir");

    /** Parent of the per-unit throwaway directories. */
    private String scratchRoot = System.getProperty("java.io.tmpdir") + "/kanban-worker";

    /** How often to ask for work when idle. */
    private long pollIntervalMs = 5000;

    /**
     * How often to check in while running. Also how promptly this worker notices a Stop, and
     * how far behind the card's live log runs — so it wants to be a good deal shorter than the
     * server's {@code agent.lease-seconds}, which is what it is renewing.
     */
    private long heartbeatIntervalMs = 3000;

    private String claudeCommand = "claude";

    private List<String> extraArgs = List.of("--output-format", "stream-json", "--verbose");

    private static String defaultName() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "worker" : host.trim();
    }

    @Override
    public String claudeCommand() {
        return claudeCommand;
    }

    @Override
    public List<String> extraArgs() {
        return extraArgs;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public String getRepoDirectory() {
        return repoDirectory;
    }

    public void setRepoDirectory(String repoDirectory) {
        this.repoDirectory = repoDirectory;
    }

    public String getScratchRoot() {
        return scratchRoot;
    }

    public void setScratchRoot(String scratchRoot) {
        this.scratchRoot = scratchRoot;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public String getClaudeCommand() {
        return claudeCommand;
    }

    public void setClaudeCommand(String claudeCommand) {
        this.claudeCommand = claudeCommand;
    }

    public List<String> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs;
    }
}
