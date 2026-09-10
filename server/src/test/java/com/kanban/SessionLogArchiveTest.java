package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.agent.LeaseRepository;
import com.kanban.todo.TodoRepository;
import com.kanban.todo.TodoStatus;
import com.kanban.worker.LiveLogRegistry;
import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading the session transcript of work that has already finished — for a board task and
 * for a workflow step alike.
 *
 * <p>The transcript used to live only in {@code LiveLogRegistry}, in memory. That is not a
 * detail these tests can take on trust, because this app restarts on every deploy: "the log
 * is still there" and "the log is still there after the process that streamed it is gone"
 * are different claims, and only the second one is the feature. Every test here therefore
 * clears the registry between writing the transcript and reading it, which is the closest a
 * single-process test can stand to a restart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=true",
        "worker.classify-with-claude=false",
        "agent.token=test-worker-token",
        "agent.lease-seconds=60",
        "agent.repo-name=Dashboard",
        "spring.datasource.url=jdbc:h2:mem:sessionlogtestdb;DB_CLOSE_DELAY=-1"
})
class SessionLogArchiveTest {

    private static final String TOKEN = "Bearer test-worker-token";

    @Autowired
    MockMvc mvc;

    @Autowired
    TodoRepository todos;

    @Autowired
    LeaseRepository leases;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @Autowired
    WorkflowExecutionRepository executionRepo;

    @Autowired
    LiveLogRegistry liveLog;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        leases.deleteAll();
        todos.deleteAll();
        nodeRuns.deleteAll();
        executionRepo.deleteAll();
    }

    // --- board tasks -----------------------------------------------------------

    @Test
    void aFinishedTasksTranscriptSurvivesTheMemoryItWasStreamedThrough() throws Exception {
        long id = createTodo("Research gold prices");
        String lease = claimTask();

        heartbeat(lease, 1, "🔧 Bash  curl -s example.com", "   ↳ ✓ 200 OK");
        report(lease, 0, "an old silent pond");

        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.RESOLVED);

        // The restart. Everything the registry knew is gone.
        liveLog.clear(id);

        JsonNode body = mapper.readTree(logOfTask(id));
        assertThat(body.get("log").asText())
                .contains("Picked up todo #" + id)   // written by the server at claim
                .contains("🔧 Bash  curl -s example.com")  // streamed by the worker
                .contains("   ↳ ✓ 200 OK")
                // The verdict line is appended after the outcome is applied, so its presence
                // is what proves the copy is taken at the end of the session and not before.
                .contains("✔ Done.");
        assertThat(body.get("archived").asBoolean()).isTrue();
    }

    @Test
    void aFailedTaskKeepsItsTranscriptToo() throws Exception {
        long id = createTodo("Deploy the thing");
        String lease = claimTask();

        heartbeat(lease, 1, "🔧 Bash  ./scripts/deploy.sh");
        report(lease, 0, "tests failed\n[[TASK_FAILED]] red build");

        assertThat(todos.findById(id).orElseThrow().getStatus()).isEqualTo(TodoStatus.FAILED);
        liveLog.clear(id);

        // The transcript of a failure is the one most worth keeping: the result field holds
        // only the last thing said, and what went wrong is usually further up.
        assertThat(mapper.readTree(logOfTask(id)).get("log").asText())
                .contains("🔧 Bash  ./scripts/deploy.sh")
                .contains("✗ Task reported failure.");
    }

    @Test
    void aRunningTaskShowsTheSessionInHandNotTheOneItReplaced() throws Exception {
        long id = createTodo("Try again");
        report(claimTask(), 0, "first answer");
        assertThat(todos.findById(id).orElseThrow().getSessionLog()).contains("✔ Done.");

        // Requeued and picked up again. The second session has said nothing yet.
        requeue(id);
        String second = claimTask();

        JsonNode body = mapper.readTree(logOfTask(id));
        // Showing the kept transcript here would present the previous attempt's session as
        // the running one — the only reading in which stale text is worse than none.
        assertThat(body.get("log").asText()).doesNotContain("✔ Done.");
        assertThat(body.get("archived").asBoolean()).isFalse();
        assertThat(todos.findById(id).orElseThrow().getSessionLog()).isNull();

        heartbeat(second, 1, "🔧 Bash  echo second");
        assertThat(mapper.readTree(logOfTask(id)).get("log").asText()).contains("echo second");
    }

    // --- workflow steps --------------------------------------------------------

    @Test
    void aWorkflowStepsSessionLinesReachItsTranscript() throws Exception {
        long runId = claimedNodeRunId();
        String lease = leaseOfNode(runId);

        heartbeat(lease, 1, "🔧 Read  sources.md", "✏️ Writing the summary");

        // The server used to drop these on the floor for anything that wasn't a board task,
        // so a running step's "Live session" box showed the two lines the server itself had
        // written and never moved again.
        assertThat(mapper.readTree(logOfNode(runId)).get("log").asText())
                .contains("🔧 Read  sources.md")
                .contains("✏️ Writing the summary");
    }

    @Test
    void aFinishedStepsTranscriptSurvivesTheMemoryItWasStreamedThrough() throws Exception {
        long runId = claimedNodeRunId();
        String lease = leaseOfNode(runId);

        heartbeat(lease, 1, "🔧 Read  sources.md");
        report(lease, 0, "three sources");

        assertThat(nodeRuns.findById(runId).orElseThrow().getStatus()).isEqualTo(RunState.SUCCEEDED);

        liveLog.clear(LiveLogRegistry.nodeKey(runId));

        JsonNode body = mapper.readTree(logOfNode(runId));
        assertThat(body.get("log").asText())
                .contains("started on worker")
                .contains("🔧 Read  sources.md")
                .contains("✔ Node done.");
        assertThat(body.get("archived").asBoolean()).isTrue();
        // The step's own answer is unaffected — the transcript is the working, not the result.
        assertThat(nodeRuns.findById(runId).orElseThrow().getOutput()).isEqualTo("three sources");
    }

    @Test
    void retryingAStepDropsTheTranscriptOfTheAttemptItReplaces() throws Exception {
        long runId = claimedNodeRunId();
        long executionId = nodeRuns.findById(runId).orElseThrow().getExecutionId();
        report(leaseOfNode(runId), 1, "it broke");
        assertThat(nodeRuns.findById(runId).orElseThrow().getSessionLog()).contains("✗ Claude exited");

        settleRun(executionId, RunState.FAILED);
        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isOk());

        // A row records one attempt. Leaving the previous session's transcript on it would
        // put a failure under a step that is about to read PENDING, then SUCCEEDED.
        assertThat(nodeRuns.findById(runId).orElseThrow().getSessionLog()).isNull();
        JsonNode body = mapper.readTree(logOfNode(runId));
        assertThat(body.get("log").asText()).isEmpty();
        assertThat(body.get("archived").asBoolean()).isFalse();
    }

    // --- helpers ---------------------------------------------------------------

    private long createTodo(String title) throws Exception {
        String body = mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"projectImprovement\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    private void requeue(long id) throws Exception {
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isOk());
    }

    /** Claim as a worker that can only take board tasks, and return its lease id. */
    private String claimTask() throws Exception {
        return claim("[\"general\"]");
    }

    /**
     * Start a run of a one-step workflow and claim its only node, returning the node run's id.
     * A worker without the {@code general} capability is used so the board queue can't be
     * handed out ahead of it.
     */
    private long claimedNodeRunId() throws Exception {
        String workflowId = mapper.readTree(mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Transcript chain","description":"one step",
                                 "nodes":[{"id":"a","name":"Collect","prompt":"Find sources"}],
                                 "edges":[]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"gold prices\"}"))
                .andExpect(status().isCreated());
        return mapper.readTree(claimUnit("[\"workflow\"]")).get("refId").asLong();
    }

    private String leaseOfNode(long runId) {
        return leases.findAll().stream()
                .filter(l -> l.getRefId().equals(runId))
                .map(com.kanban.agent.Lease::getId)
                .findFirst().orElseThrow();
    }

    private void settleRun(long executionId, RunState status) {
        WorkflowExecution execution = executionRepo.findById(executionId).orElseThrow();
        execution.setStatus(status);
        executionRepo.save(execution);
    }

    private String claimUnit(String capabilities) throws Exception {
        return mvc.perform(post("/api/agent/claim")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"laptop-01\",\"capabilities\":" + capabilities
                                + ",\"protocolVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String claim(String capabilities) throws Exception {
        return mapper.readTree(claimUnit(capabilities)).get("leaseId").asText();
    }

    private void heartbeat(String lease, long seq, String... lines) throws Exception {
        StringBuilder json = new StringBuilder("{\"seq\":" + seq + ",\"logLines\":[");
        for (int i = 0; i < lines.length; i++) {
            json.append(i == 0 ? "" : ",").append(mapper.writeValueAsString(lines[i]));
        }
        json.append("]}");
        mvc.perform(post("/api/agent/leases/" + lease + "/heartbeat")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isOk());
    }

    private void report(String lease, int exitCode, String finalOutput) throws Exception {
        mvc.perform(post("/api/agent/leases/" + lease + "/result")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exitCode\":" + exitCode + ",\"timedOut\":false,"
                                + "\"abandonedBackgroundWork\":false,\"stopped\":false,"
                                + "\"finalOutput\":" + mapper.writeValueAsString(finalOutput)
                                + ",\"costUsd\":null,\"durationMs\":null}"))
                .andExpect(status().isNoContent());
    }

    /**
     * Read as UTF-8 explicitly. A transcript is full of the CLI's box-drawing and status
     * glyphs, and MockMvc's response defaults to ISO-8859-1 when the content type carries no
     * charset — which silently turns every one of them into mojibake here and nowhere else.
     */
    private String logOfTask(long id) throws Exception {
        return mvc.perform(get("/api/todos/" + id + "/log"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String logOfNode(long runId) throws Exception {
        return mvc.perform(get("/api/workflows/node-runs/" + runId + "/log"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
