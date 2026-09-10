package com.kanban;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import com.kanban.workflow.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Card #334. Runs vanished from this board and the app could not say who took them,
 * because neither delete path wrote anything down; answering card #333 meant reading
 * twenty rotated database snapshots. Two things are pinned here:
 *
 * <ul>
 *   <li>every deletion leaves a line naming what it destroyed — through BOTH paths,
 *       the single run and the workflow cascade;</li>
 *   <li>the run count the delete-a-workflow confirm relies on comes from the server
 *       and is not the capped page the panel lists.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:workflowaudittestdb;DB_CLOSE_DELAY=-1"
})
class WorkflowDeletionAuditTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowExecutionRepository executionRepo;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Captures what WorkflowService actually logged, rather than trusting that it did. */
    private ListAppender<ILoggingEvent> logged;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void captureTheLog() {
        serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkflowService.class);
        logged = new ListAppender<>();
        logged.start();
        serviceLogger.addAppender(logged);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseTheLog() {
        serviceLogger.detachAppender(logged);
    }

    private List<String> lines() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final String ONE_STEP = """
            {"name":"NAME","description":"one step",
             "nodes":[{"id":"a","name":"Write","prompt":"Write something"}],
             "edges":[]}
            """;

    private String createWorkflow(String name) throws Exception {
        String body = mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONE_STEP.replace("NAME", name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private long queueRun(String workflowId) throws Exception {
        String body = mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":null}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    // --- the log line ----------------------------------------------------

    @Test
    void deletingOneRunSaysWhichRunAndWhatItWasWorth() throws Exception {
        String workflowId = createWorkflow("Audited");
        long runId = queueRun(workflowId);

        // A finished, billed run: the case where the line has something to be sorry about.
        WorkflowExecution execution = executionRepo.findById(runId).orElseThrow();
        execution.setStatus(RunState.SUCCEEDED);
        execution.setCostUsd(0.1116);
        executionRepo.save(execution);

        mvc.perform(delete("/api/workflows/executions/" + runId))
                .andExpect(status().isNoContent());

        assertThat(lines())
                .as("the deletion has to name the run, its workflow, its state and its cost")
                .anySatisfy(line -> assertThat(line)
                        .contains("Deleted run #" + runId)
                        .contains("'Audited'")
                        .contains("SUCCEEDED")
                        .contains("1 step")
                        .contains("$0.1116"));
    }

    @Test
    void aRunNobodyWasBilledForStillSaysSoRatherThanPrintingNull() throws Exception {
        long runId = queueRun(createWorkflow("Unbilled"));

        mvc.perform(delete("/api/workflows/executions/" + runId))
                .andExpect(status().isNoContent());

        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .contains("Deleted run #" + runId)
                .contains("no recorded cost")
                .doesNotContain("null"));
    }

    @Test
    void deletingAWorkflowRecordsTheCascadeAndEveryRunItTookWithIt() throws Exception {
        String workflowId = createWorkflow("Doomed");
        long first = queueRun(workflowId);
        long second = queueRun(workflowId);

        mvc.perform(delete("/api/workflows/" + workflowId))
                .andExpect(status().isNoContent());

        assertThat(lines())
                .as("the cascade announces itself before it starts")
                .anySatisfy(line -> assertThat(line)
                        .contains("Deleting workflow 'Doomed'")
                        .contains("2 run(s)"));
        // And each run is still named individually — a count alone would not let anyone
        // work out afterwards WHICH runs went.
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("Deleted run #" + first));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("Deleted run #" + second));
    }

    // --- the count the confirm depends on --------------------------------

    @Test
    void theCountEndpointScopesToOneWorkflow() throws Exception {
        String mine = createWorkflow("Mine");
        String theirs = createWorkflow("Theirs");
        queueRun(mine);
        queueRun(mine);
        queueRun(theirs);

        mvc.perform(get("/api/workflows/executions/count").param("workflowId", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/workflows/executions/count").param("workflowId", theirs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void theCountIsOfEveryRunTheWorkflowHasEvenPastThePageTheListStopsAt() throws Exception {
        String workflowId = createWorkflow("Busy");
        int pageSize = mvc.perform(get("/api/workflows/executions/count"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .transform(body -> readInt(body, "pageSize"));

        int runs = pageSize + 3;
        for (int i = 0; i < runs; i++) {
            queueRun(workflowId);
        }

        // This is the whole bug: the page the browser was counting stops short…
        String listed = mvc.perform(get("/api/workflows/executions").param("workflowId", workflowId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(listed)).hasSize(pageSize);

        // …while the number that actually gets destroyed is this one.
        mvc.perform(get("/api/workflows/executions/count").param("workflowId", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(runs))
                .andExpect(jsonPath("$.pageSize").value(pageSize));
    }

    /** The count with no scope covers every workflow, which is what "All" asks for. */
    @Test
    void anUnscopedCountCountsEveryRunOnTheBoard() throws Exception {
        long before = readTotal(null);
        queueRun(createWorkflow("Counted"));
        assertThat(readTotal(null)).isEqualTo(before + 1);
    }

    private long readTotal(String workflowId) throws Exception {
        var request = get("/api/workflows/executions/count");
        if (workflowId != null) {
            request = request.param("workflowId", workflowId);
        }
        String body = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("total").asLong();
    }

    private int readInt(String body, String field) {
        try {
            JsonNode node = mapper.readTree(body);
            return node.get(field).asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
