package com.kanban;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.workflow.RunState;
import com.kanban.workflow.WorkflowExecution;
import com.kanban.workflow.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giving one run a name of its own.
 *
 * <p>Runs of a workflow all carry that workflow's name, so the list could tell them
 * apart only by number and by how long ago they started — useless in the case that
 * makes run history worth keeping at all: the same graph tried four ways. The name is
 * per-run and nothing else moves with it; the rules worth pinning down are what "no
 * name" means, that a blank clears rather than being refused, and that this is the one
 * edit to a run that does not wait for the run to finish.
 *
 * <p>The worker is disabled, so a queued run stays PENDING and no Claude process is
 * ever launched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:runnametestdb;DB_CLOSE_DELAY=-1"
})
class WorkflowRunNameApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowExecutionRepository executionRepo;

    private final ObjectMapper mapper = new ObjectMapper();

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
                        .content("{\"input\":\"gold prices\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    private static String named(String name) {
        return "{\"name\":" + (name == null ? "null" : "\"" + name + "\"") + "}";
    }

    private void rename(long runId, String name, int expectedStatus) throws Exception {
        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content(named(name)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void aRunStartsWithNoNameOfItsOwnAndKeepsTheOneItIsGiven() throws Exception {
        long runId = queueRun(createWorkflow("Research"));

        // Absent, not "Research": the field says whether this run was named, and the
        // page words itself differently when it was.
        mvc.perform(get("/api/workflows/executions/" + runId))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.workflowName", is("Research")));

        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content(named("Tighter brief")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Tighter brief")))
                // The run is otherwise exactly as it was — this is a label, not an edit.
                .andExpect(jsonPath("$.workflowName", is("Research")))
                .andExpect(jsonPath("$.input", is("gold prices")))
                .andExpect(jsonPath("$.status", is("PENDING")));

        // And it is a fact about the run, not about the response that set it.
        mvc.perform(get("/api/workflows/executions/" + runId))
                .andExpect(jsonPath("$.name", is("Tighter brief")));
        mvc.perform(get("/api/workflows/executions"))
                .andExpect(jsonPath("$[0].name", is("Tighter brief")));
    }

    @Test
    void namingOneRunLeavesItsSiblingsAndItsWorkflowAlone() throws Exception {
        String workflowId = createWorkflow("Research");
        long first = queueRun(workflowId);
        long second = queueRun(workflowId);

        rename(first, "Tighter brief", 200);

        mvc.perform(get("/api/workflows/executions/" + second))
                .andExpect(jsonPath("$.name").doesNotExist());
        // The workflow is what every future run is named after; renaming one run must
        // not quietly rename the graph.
        mvc.perform(get("/api/workflows/" + workflowId))
                .andExpect(jsonPath("$.name", is("Research")));
    }

    @Test
    void blankingTheBoxTakesTheNameAwayRatherThanStoringAnEmptyOne() throws Exception {
        long runId = queueRun(createWorkflow("Research"));
        rename(runId, "Tighter brief", 200);

        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content(named("   ")))
                .andExpect(status().isOk())
                // Back to the state it started in — not "" , which would be a third
                // thing the page would have to tell apart from both.
                .andExpect(jsonPath("$.name").doesNotExist());
        assertThat(executionRepo.findById(runId).orElseThrow().getName()).isNull();

        // An omitted name means the same as a blank one: take it away.
        rename(runId, "Tighter brief", 200);
        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void surroundingSpaceIsTrimmedSoTwoRunsCannotBeNamedTheSameThingInvisibly() throws Exception {
        long runId = queueRun(createWorkflow("Research"));

        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content(named("  Tighter brief  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Tighter brief")));
    }

    @Test
    void aRunStillGoingCanBeNamedTooUnlikeEveryOtherEditToARun() throws Exception {
        // Rewriting a step waits for the run to settle, because it changes what a
        // session will be asked to do and must not race the runner. A name changes
        // nothing of the sort, and the moment you know what to call a run is usually
        // while you are watching it.
        long runId = queueRun(createWorkflow("Research"));
        WorkflowExecution execution = executionRepo.findById(runId).orElseThrow();
        execution.setStatus(RunState.RUNNING);
        executionRepo.save(execution);

        mvc.perform(put("/api/workflows/executions/" + runId + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content(named("The live one")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("The live one")))
                .andExpect(jsonPath("$.status", is("RUNNING")));
    }

    @Test
    void theNameSurvivesARetryBecauseItIsTheSameRunAgain() throws Exception {
        long runId = queueRun(createWorkflow("Research"));
        rename(runId, "Tighter brief", 200);

        WorkflowExecution execution = executionRepo.findById(runId).orElseThrow();
        execution.setStatus(RunState.FAILED);
        executionRepo.save(execution);

        mvc.perform(post("/api/workflows/executions/" + runId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Tighter brief")));
    }

    @Test
    void aNameLongerThanAWorkflowsIsRefusedRatherThanTruncated() throws Exception {
        long runId = queueRun(createWorkflow("Research"));

        rename(runId, "n".repeat(200), 200);
        rename(runId, "n".repeat(201), 400);
        // The refusal left the run with the name it already had, not half of the new one.
        mvc.perform(get("/api/workflows/executions/" + runId))
                .andExpect(jsonPath("$.name", is("n".repeat(200))));
    }

    @Test
    void namingARunThatIsNotThereIs404() throws Exception {
        rename(999_999L, "Nowhere", 404);
    }
}
