package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.workflow.NodeExecution;
import com.kanban.workflow.NodeExecutionRepository;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing a run's own copy of the graph: rewriting a step's whole settings, adding a
 * step the workflow never had, and taking one out again.
 *
 * <p>The three exist so a run can be <em>debugged</em>. Before them the only handle on
 * a disappointing run was the wording of one prompt; every other question it raised —
 * was this step worth the effort level it got, should it have been reading the step two
 * above as well, is the real problem a step that is missing — could only be asked by
 * editing the workflow and paying for the whole graph to find out.
 *
 * <p>The worker is disabled, so a queued run stays PENDING and no Claude process is
 * ever launched. Runs are settled by writing to the repositories, exactly as
 * {@code WorkflowApiTest} does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:runeditingtestdb;DB_CLOSE_DELAY=-1"
})
class WorkflowRunEditingApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    NodeExecutionRepository nodeRuns;

    @Autowired
    WorkflowExecutionRepository executionRepo;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A → B → C, entry inferred from A being the only node with no input. */
    private static String chainNamed(String name) {
        return """
                {"name":"%s","description":"three steps",
                 "nodes":[{"id":"a","name":"Collect","prompt":"Find sources"},
                          {"id":"b","name":"Summarise","prompt":"Summarise the input"},
                          {"id":"c","name":"Publish","prompt":"Write it up"}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}
                """.formatted(name);
    }

    private String createWorkflow(String body) throws Exception {
        String json = mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    private long startRun(String workflowId) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"gold prices\"}"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** Put a node run into a terminal state, as the runner would have. */
    private void settle(long executionId, String nodeId, RunState status, String outputOrError) {
        NodeExecution run = nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow();
        run.setStatus(status);
        if (status == RunState.SUCCEEDED) {
            run.setOutput(outputOrError);
        } else {
            run.setError(outputOrError);
        }
        nodeRuns.save(run);
    }

    private void settleRun(long executionId, RunState status) {
        WorkflowExecution execution = executionRepo.findById(executionId).orElseThrow();
        execution.setStatus(status);
        executionRepo.save(execution);
    }

    private long nodeRunId(long executionId, String nodeId) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow().getId();
    }

    /** A three-step chain that has run to the end, ready to be picked apart. */
    private long finishedRun(String name) throws Exception {
        long executionId = startRun(createWorkflow(chainNamed(name)));
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "the wrong angle");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);
        return executionId;
    }

    private static String settings(String name, String prompt, String effort, String... inputs) {
        StringBuilder wired = new StringBuilder();
        for (String input : inputs) {
            wired.append(wired.length() == 0 ? "" : ",").append('"').append(input).append('"');
        }
        return """
                {"name":"%s","prompt":"%s","effort":%s,"inputs":[%s]}
                """.formatted(name, prompt, effort == null ? "null" : "\"" + effort + "\"", wired);
    }

    // --- rewriting a step -------------------------------------------------

    @Test
    void everySettingOfAStepCanBeRewrittenInsideOneRun() throws Exception {
        String workflowId = createWorkflow(chainNamed("Rewrite a step"));
        long executionId = startRun(workflowId);
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "the wrong angle");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise hard", "One paragraph, no hedging", "max", "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].nodeName", is("Summarise hard")))
                .andExpect(jsonPath("$.nodes[1].prompt", is("One paragraph, no hedging")))
                .andExpect(jsonPath("$.nodes[1].effort", is("max")))
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").exists())
                // Nothing has been re-run, so the answer on the row is still the old
                // settings' — the edit alone must not disturb the record.
                .andExpect(jsonPath("$.nodes[1].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[1].output", is("the wrong angle")))
                // Only that step; the rest of the run is still the workflow's.
                .andExpect(jsonPath("$.nodes[0].nodeName", is("Collect")))
                .andExpect(jsonPath("$.nodes[0].settingsEditedAt").doesNotExist());

        // The workflow itself is untouched: an experiment, not a change of policy.
        mvc.perform(get("/api/workflows/" + workflowId))
                .andExpect(jsonPath("$.nodes[1].name", is("Summarise")))
                .andExpect(jsonPath("$.nodes[1].prompt", is("Summarise the input")))
                .andExpect(jsonPath("$.nodes[1].effort").doesNotExist());

        // And the re-run carries all of it into the next attempt.
        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[1].attempt", is(2)))
                .andExpect(jsonPath("$.nodes[1].effort", is("max")))
                .andExpect(jsonPath("$.nodes[1].prompt", is("One paragraph, no hedging")));
    }

    @Test
    void renamingAStepDoesNotMarkItsAnswerOutOfDate() throws Exception {
        // The name is the only one of the four settings that cannot change a character
        // of what the session is asked to do, so stamping it would put a "this answer
        // is out of date" warning over an answer that is perfectly current.
        long executionId = finishedRun("Rename only");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Boil it down", "Summarise the input", null, "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].nodeName", is("Boil it down")))
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").doesNotExist());
    }

    @Test
    void raisingAStepsEffortMarksItsAnswerOutOfDate() throws Exception {
        // The other half of the rule above: effort changes what the session does, so an
        // answer produced at the old level is no longer this step's work.
        long executionId = finishedRun("Effort stamp");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", "xhigh", "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].effort", is("xhigh")))
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").exists());
    }

    @Test
    void aStepCanBeRewiredToReadFromSomewhereElse() throws Exception {
        // "Publish" was reading the summary; make it read the sources as well. This is
        // the question the prompt box could never ask.
        long executionId = finishedRun("Rewire");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "c"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Publish", "Write it up", null, "a", "b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[2].dependsOn", hasSize(2)))
                .andExpect(jsonPath("$.nodes[2].dependsOn[0]", is("a")))
                .andExpect(jsonPath("$.nodes[2].dependsOn[1]", is("b")))
                .andExpect(jsonPath("$.nodes[2].settingsEditedAt").exists());
    }

    @Test
    void askingForTheSameInputTwiceIsOneInput() throws Exception {
        long executionId = finishedRun("Duplicate wiring");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", null, "a", "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].dependsOn", hasSize(1)))
                // Folded, not stored twice — so it is not an edit either.
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").doesNotExist());
    }

    @Test
    void wiringThatWouldLoopIsRefused() throws Exception {
        // "Collect" reading from "Publish" closes the chain into a ring, and the runner
        // would then never finish it.
        long executionId = finishedRun("Loop");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", null, "c")))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("would make a loop"));
    }

    @Test
    void aStepCannotBeLeftWithNothingToReadFrom() throws Exception {
        long executionId = finishedRun("Cut off");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", null)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("at least one step to read from"));
    }

    @Test
    void aStepCannotBeToldToReadFromSomethingThatIsNotInTheRun() throws Exception {
        long executionId = finishedRun("Stranger");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", null, "nowhere")))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("no such step in this run"));
    }

    @Test
    void theStepARunStartedFromIsNotGivenSomethingToReadFrom() throws Exception {
        // The entry is handed the run's own input. Wiring it to another step would
        // silently replace that with the step's output.
        long executionId = finishedRun("Entry wiring");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Collect", "Find sources", null, "b")))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("run's own input"));
    }

    @Test
    void anEffortLevelThatIsNotOneIsRefusedWithTheLevelsNamed() throws Exception {
        long executionId = finishedRun("Bad effort");

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Summarise the input", "turbo", "a")))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("ultracode"));
    }

    // --- adding a step ----------------------------------------------------

    @Test
    void aStepAddedToARunIsQueuedAndStartsNothing() throws Exception {
        // A step is a Claude session with a real bill, so adding one and running one
        // are two decisions: this one costs nothing.
        String workflowId = createWorkflow(chainNamed("Add a step"));
        long executionId = startRun(workflowId);
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "a summary");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);

        String json = mvc.perform(post("/api/workflows/executions/" + executionId + "/node-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Fact-check", "Check the summary against the sources",
                                "high", "b")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodes", hasSize(4)))
                // The run itself did not go back into the queue.
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andReturn().getResponse().getContentAsString();

        JsonNode added = added(json);
        assertThat(added.get("status").asText()).isEqualTo("PENDING");
        assertThat(added.get("effort").asText()).isEqualTo("high");
        assertThat(added.get("addedAt").isNull()).isFalse();
        // Nothing behind it, so it is on its first attempt and not its second.
        assertThat(added.get("attempt").asInt()).isEqualTo(1);
        assertThat(added.get("dependsOn").get(0).asText()).isEqualTo("b");
        // It reads a step that succeeded, so it can be started by itself.
        assertThat(added.get("canRerunAlone").asBoolean()).isTrue();

        // The workflow it came from never had it.
        mvc.perform(get("/api/workflows/" + workflowId))
                .andExpect(jsonPath("$.nodes", hasSize(3)));
    }

    @Test
    void anAddedStepRunsAsAFirstAttemptWhenItIsStarted() throws Exception {
        // Starting it goes through the same re-run path as any other step, and that
        // path bumps the attempt counter. A step that has never run has nothing to
        // count, so "attempt 2" would be a history the page invented.
        long executionId = finishedRun("Start an added step");

        String json = mvc.perform(post("/api/workflows/executions/" + executionId + "/node-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Fact-check", "Check it", null, "b")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long addedId = added(json).get("id").asLong();

        mvc.perform(post("/api/workflows/node-runs/" + addedId + "/retry"))
                .andExpect(status().isOk())
                // Queued for real this time — the run is live again.
                .andExpect(jsonPath("$.status", is("PENDING")));
        assertThat(nodeRuns.findById(addedId).orElseThrow().getAttempt()).isEqualTo(1);
    }

    @Test
    void anAddedStepIsNumberedWhereItSitsRatherThanLast() throws Exception {
        // orderIndex is the run's display order, so a step inserted in the middle has
        // to land in the middle — a panel showing a step above the one that feeds it
        // is the picture saying the opposite of what the graph does.
        long executionId = finishedRun("Numbering");

        mvc.perform(post("/api/workflows/executions/" + executionId + "/node-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Fact-check", "Check it", null, "a")))
                .andExpect(status().isCreated())
                // a, then the two that read it, then c which reads b.
                .andExpect(jsonPath("$.nodes[0].nodeId", is("a")))
                .andExpect(jsonPath("$.nodes[1].nodeId", is("b")))
                .andExpect(jsonPath("$.nodes[2].nodeName", is("Fact-check")))
                .andExpect(jsonPath("$.nodes[3].nodeId", is("c")));
    }

    @Test
    void aStepCannotBeAddedWithNothingToReadFrom() throws Exception {
        // A run has exactly one root and it is the step it started from. A second one
        // added afterwards would be a branch nothing leads to.
        long executionId = finishedRun("Rootless");

        mvc.perform(post("/api/workflows/executions/" + executionId + "/node-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Fact-check", "Check it", null)))
                .andExpect(status().isBadRequest());
    }

    // --- removing a step --------------------------------------------------

    @Test
    void removingAStepSplicesTheChainShutBehindIt() throws Exception {
        // A → B → C without B is meant to be A → C. Leaving C pointing at a step that
        // no longer exists would only get it marked SKIPPED on the next tick.
        long executionId = finishedRun("Splice");

        mvc.perform(delete("/api/workflows/node-runs/" + nodeRunId(executionId, "b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.nodes[0].nodeId", is("a")))
                .andExpect(jsonPath("$.nodes[1].nodeId", is("c")))
                .andExpect(jsonPath("$.nodes[1].dependsOn", hasSize(1)))
                .andExpect(jsonPath("$.nodes[1].dependsOn[0]", is("a")))
                // C's stored answer was written from B's output, which is gone.
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").exists());

        assertThat(nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId)).hasSize(2);
    }

    @Test
    void theStepARunStartedFromCannotBeRemoved() throws Exception {
        // Every other step is recorded as reachable from it, and the retry rules are
        // all defined against it — removing it makes the record unreadable rather than
        // shorter.
        long executionId = finishedRun("Keep the entry");

        mvc.perform(delete("/api/workflows/node-runs/" + nodeRunId(executionId, "a")))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("started from"));
    }

    @Test
    void removingAStepLeavesTheWorkflowAloneLikeEveryOtherRunEdit() throws Exception {
        String workflowId = createWorkflow(chainNamed("Delete keeps the graph"));
        long executionId = startRun(workflowId);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settle(executionId, "c", RunState.SUCCEEDED, "three");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(delete("/api/workflows/node-runs/" + nodeRunId(executionId, "c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(2)));

        mvc.perform(get("/api/workflows/" + workflowId))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.edges", hasSize(2)));
    }

    @Test
    void removingAStepThatIsNotThereAnswers404() throws Exception {
        mvc.perform(delete("/api/workflows/node-runs/999999")).andExpect(status().isNotFound());
    }

    // --- none of it while the run is going --------------------------------

    @Test
    void aLiveRunIsNotEditedUnderneathTheRunner() throws Exception {
        // The runner's lane walks these rows and its whole claim to needing no locking
        // is that nothing else touches them.
        String workflowId = createWorkflow(chainNamed("Live"));
        long executionId = startRun(workflowId);
        settleRun(executionId, RunState.RUNNING);
        long stepId = nodeRunId(executionId, "b");

        mvc.perform(put("/api/workflows/node-runs/" + stepId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Summarise", "Something else", null, "a")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/workflows/executions/" + executionId + "/node-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Fact-check", "Check it", null, "b")))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/workflows/node-runs/" + stepId))
                .andExpect(status().isConflict());
    }

    /** The one node of the returned run that the workflow never had. */
    private JsonNode added(String executionJson) throws Exception {
        for (JsonNode node : mapper.readTree(executionJson).get("nodes")) {
            if (!node.get("addedAt").isNull()) {
                return node;
            }
        }
        throw new AssertionError("no added step in " + executionJson);
    }
}
