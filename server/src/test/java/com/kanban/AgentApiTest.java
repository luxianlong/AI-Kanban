package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.agent.LeaseState;
import com.kanban.agent.LeaseRepository;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worker-facing API, end to end.
 *
 * <p>The in-process worker is off here, so nothing competes for the tasks these tests create
 * and every pickup is one this test made through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Work must actually be handed out here — worker.enabled is the board's master
        // switch for that now, not just a brake on a poller that no longer exists.
        "worker.enabled=true",
        // ...but nothing may shell out to the CLI: the story classifier is the one thing the
        // server still launches itself, and it rides on the same switch.
        "worker.classify-with-claude=false",
        "agent.token=test-worker-token",
        "agent.lease-seconds=60",
        "agent.repo-name=Dashboard",
        "spring.datasource.url=jdbc:h2:mem:agenttestdb;DB_CLOSE_DELAY=-1"
})
class AgentApiTest {

    private static final String TOKEN = "Bearer test-worker-token";

    @Autowired
    MockMvc mvc;

    @Autowired
    TodoRepository todos;

    @Autowired
    LeaseRepository leases;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        leases.deleteAll();
        todos.deleteAll();
    }

    private long createTodo(String title, boolean projectImprovement) throws Exception {
        String body = mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"projectImprovement\":"
                                + projectImprovement + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    private String claim(String worker, String capabilities) throws Exception {
        return mvc.perform(post("/api/agent/claim")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"" + worker + "\",\"capabilities\":" + capabilities
                                + ",\"protocolVersion\":1}"))
                .andReturn().getResponse().getContentAsString();
    }

    // --- authentication --------------------------------------------------------

    @Test
    void claimingWithoutATokenIsRefused() throws Exception {
        mvc.perform(post("/api/agent/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"w\",\"capabilities\":[\"general\"],\"protocolVersion\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claimingWithTheWrongTokenIsRefused() throws Exception {
        mvc.perform(post("/api/agent/claim")
                        .header("Authorization", "Bearer not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"w\",\"capabilities\":[\"general\"],\"protocolVersion\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // --- the happy path --------------------------------------------------------

    @Test
    void aClaimedTaskComesBackAsAWorkUnitAndGoesInProgress() throws Exception {
        long id = createTodo("Research gold prices", false);

        JsonNode unit = mapper.readTree(claim("laptop-01", "[\"general\"]"));

        assertThat(unit.get("kind").asText()).isEqualTo("TASK");
        assertThat(unit.get("refId").asLong()).isEqualTo(id);
        assertThat(unit.get("leaseId").asText()).isNotBlank();
        // The prompt is built by the server: a worker has no database to build it from.
        assertThat(unit.get("prompt").asText()).contains("Research gold prices");
        assertThat(unit.get("limits").get("timeoutSeconds").asLong()).isPositive();
        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
    }

    @Test
    void reportingACleanRunResolvesTheTask() throws Exception {
        long id = createTodo("Write a haiku", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();

        mvc.perform(post("/api/agent/leases/" + lease + "/result")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitCode":0,"timedOut":false,"abandonedBackgroundWork":false,
                                 "stopped":false,"finalOutput":"an old silent pond","costUsd":0.12,
                                 "durationMs":45000}"""))
                .andExpect(status().isNoContent());

        var todo = todos.findById(id).orElseThrow();
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.RESOLVED);
        assertThat(todo.getResult()).isEqualTo("an old silent pond");
        assertThat(todo.getCostUsd()).isEqualTo(0.12);
        assertThat(leases.findById(lease).orElseThrow().getState()).isEqualTo(LeaseState.DONE);
    }

    @Test
    void theServerNotTheWorkerDecidesThatASentinelMeansFailure() throws Exception {
        long id = createTodo("Deploy the thing", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();

        // Exit code 0 and no timeout: by every fact the worker reports this looks fine. It is
        // the sentinel in the output that makes it a failure, and reading that is the server's
        // job precisely so an old worker cannot disagree.
        mvc.perform(post("/api/agent/leases/" + lease + "/result")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitCode":0,"timedOut":false,"abandonedBackgroundWork":false,
                                 "stopped":false,"finalOutput":"tests failed\\n[[TASK_FAILED]] red build",
                                 "costUsd":null,"durationMs":null}"""))
                .andExpect(status().isNoContent());

        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.FAILED);
    }

    @Test
    void aCleanExitAfterAbandonedBackgroundWorkIsStillAFailure() throws Exception {
        long id = createTodo("Run the big workflow", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();

        mvc.perform(post("/api/agent/leases/" + lease + "/result")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitCode":0,"timedOut":false,"abandonedBackgroundWork":true,
                                 "stopped":false,"finalOutput":"I'll report back when it lands",
                                 "costUsd":null,"durationMs":null}"""))
                .andExpect(status().isNoContent());

        var todo = todos.findById(id).orElseThrow();
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.FAILED);
        // The promise the session wrote must not be filed as the answer.
        assertThat(todo.getResult()).contains("describes results it never received");
    }

    // --- capabilities ----------------------------------------------------------

    @Test
    void aRepoBoundTaskIsNotOfferedToAWorkerWithoutTheRepo() throws Exception {
        long id = createTodo("Fix the board layout", true);

        // This worker can do general work, but this task edits the repo and runs the deploy.
        mvc.perform(post("/api/agent/claim")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"laptop-01\",\"capabilities\":[\"general\"],\"protocolVersion\":1}"))
                .andExpect(status().isNoContent());
        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.PENDING);

        // A worker that has the repo gets it.
        JsonNode unit = mapper.readTree(claim("home-box", "[\"general\",\"repo:Dashboard\"]"));
        assertThat(unit.get("refId").asLong()).isEqualTo(id);
        assertThat(unit.get("workDirMode").asText()).isEqualTo("REPO");
    }

    // --- lease lifecycle -------------------------------------------------------

    @Test
    void heartbeatCarriesStopBackToTheWorker() throws Exception {
        long id = createTodo("Long job", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();

        String before = mvc.perform(post("/api/agent/leases/" + lease + "/heartbeat")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"logLines\":[\"working\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(before).get("stopRequested").asBoolean()).isFalse();
        assertThat(mapper.readTree(before).get("known").asBoolean()).isTrue();

        mvc.perform(post("/api/todos/" + id + "/stop")).andExpect(status().isOk());

        String after = mvc.perform(post("/api/agent/leases/" + lease + "/heartbeat")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":2,\"logLines\":[]}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(after).get("stopRequested").asBoolean()).isTrue();
    }

    @Test
    void anUnknownLeaseTellsTheWorkerToGiveUpRatherThanErroring() throws Exception {
        String body = mvc.perform(post("/api/agent/leases/no-such-lease/heartbeat")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"logLines\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).get("known").asBoolean()).isFalse();
    }

    @Test
    void releasingHandsTheTaskStraightBackToTheQueue() throws Exception {
        long id = createTodo("Interrupted job", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();

        mvc.perform(post("/api/agent/leases/" + lease + "/release").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());

        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(leases.findById(lease).orElseThrow().getState()).isEqualTo(LeaseState.RELEASED);
    }

    @Test
    void aReportOnAReleasedLeaseIsRefused() throws Exception {
        createTodo("Interrupted job", false);
        String lease = mapper.readTree(claim("laptop-01", "[\"general\"]")).get("leaseId").asText();
        mvc.perform(post("/api/agent/leases/" + lease + "/release").header("Authorization", TOKEN));

        // The work went back to the queue and may already be running elsewhere; a late answer
        // from the worker that dropped it must not be written onto it.
        mvc.perform(post("/api/agent/leases/" + lease + "/result")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitCode":0,"timedOut":false,"abandonedBackgroundWork":false,
                                 "stopped":false,"finalOutput":"late answer","costUsd":null,
                                 "durationMs":null}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void aStaleWorkerIsToldToUpdateRatherThanHandedWork() throws Exception {
        createTodo("Research gold prices", false);

        mvc.perform(post("/api/agent/claim")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"old\",\"capabilities\":[\"general\"],\"protocolVersion\":0}"))
                .andExpect(status().isBadRequest());
    }
}
