package com.kanban.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.protocol.AgentProtocol;
import com.kanban.protocol.ClaimRequest;
import com.kanban.protocol.HeartbeatRequest;
import com.kanban.protocol.HeartbeatResponse;
import com.kanban.protocol.ResultRequest;
import com.kanban.protocol.WorkUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The worker's side of the agent protocol: plain HTTP, outbound only.
 *
 * <p>Outbound only is the property that matters. A worker never listens for anything, which is
 * what lets one run on a laptop behind NAT, on a home connection, or anywhere else the server
 * could not possibly reach. Everything the server needs to tell this worker — most importantly
 * "stop" — comes back as the answer to a call the worker made.
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final WorkerSettings settings;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http;

    public AgentClient(WorkerSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Thrown when the server refuses this worker outright — a bad token, or a stale protocol. */
    public static class WorkerRejected extends RuntimeException {
        public WorkerRejected(String message) {
            super(message);
        }
    }

    /** Ask for one unit of work. Empty means there is nothing this worker can run. */
    public Optional<WorkUnit> claim() throws IOException, InterruptedException {
        ClaimRequest request = new ClaimRequest(settings.getName(), settings.getCapabilities(),
                AgentProtocol.VERSION);
        HttpResponse<String> response = send("/claim", mapper.writeValueAsString(request));
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        if (response.statusCode() == 401 || response.statusCode() == 503) {
            throw new WorkerRejected("The server refused this worker (HTTP "
                    + response.statusCode() + "). Check worker.token matches the server's "
                    + "agent.token, and that the server has one configured at all.");
        }
        if (response.statusCode() == 400) {
            throw new WorkerRejected("The server rejected this worker: " + response.body());
        }
        if (response.statusCode() != 200) {
            throw new IOException("claim failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return Optional.of(mapper.readValue(response.body(), WorkUnit.class));
    }

    /** Renew the lease and hand over whatever the session has said since the last check-in. */
    public HeartbeatResponse heartbeat(String leaseId, HeartbeatRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                send("/leases/" + leaseId + "/heartbeat", mapper.writeValueAsString(request));
        if (response.statusCode() != 200) {
            throw new IOException("heartbeat failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), HeartbeatResponse.class);
    }

    /** Report the facts of a finished session. The server decides what they mean. */
    public void report(String leaseId, ResultRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                send("/leases/" + leaseId + "/result", mapper.writeValueAsString(request));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("result failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    /** Hand work back deliberately, so the board does not have to wait out the lease. */
    public void release(String leaseId) {
        try {
            send("/leases/" + leaseId + "/release", "");
        } catch (Exception e) {
            // Best-effort by definition: this runs while shutting down, and if it does not get
            // through the lease simply expires and the server recovers the work anyway.
            log.warn("Could not release lease {} on the way down: {}", leaseId, e.toString());
        }
    }

    /** Fetch one of a task's attached files into the session's working directory. */
    public Path download(String url, Path into) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(absolute(url))
                .header(AgentProtocol.AUTH_HEADER, "Bearer " + settings.getToken())
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build();
        Files.createDirectories(into.getParent());
        HttpResponse<Path> response =
                http.send(request, HttpResponse.BodyHandlers.ofFile(into));
        if (response.statusCode() != 200) {
            throw new IOException("attachment download failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<String> send(String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(absolute(AgentProtocol.BASE_PATH + path))
                .header(AgentProtocol.AUTH_HEADER, "Bearer " + settings.getToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI absolute(String pathOrUrl) {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return URI.create(pathOrUrl);
        }
        String base = settings.getServerUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + pathOrUrl);
    }
}
