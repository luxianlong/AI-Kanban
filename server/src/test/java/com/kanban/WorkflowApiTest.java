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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the workflow API with the worker disabled, so a queued run
 * stays PENDING and no Claude process is ever launched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:workflowtestdb;DB_CLOSE_DELAY=-1"
})
class WorkflowApiTest {

    @Autowired
    MockMvc mvc;

    /**
     * The worker is off in this test, so no node ever actually runs. A run that has
     * failed part-way — the only interesting input to retry — is therefore written
     * straight to the repositories rather than waited for.
     */
    @Autowired
    NodeExecutionRepository nodeRuns;

    @Autowired
    WorkflowExecutionRepository executionRepo;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A → B → C, entry inferred from A being the only node with no input. */
    private static final String CHAIN = """
            {"name":"Research","description":"three steps",
             "nodes":[{"id":"a","name":"Collect","prompt":"Find sources"},
                      {"id":"b","name":"Summarise","prompt":"Summarise the input"},
                      {"id":"c","name":"Publish","prompt":"Write it up"}],
             "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}
            """;

    /** The same three-step chain under a given name, so a test owns its own names. */
    private static String chainNamed(String name) {
        return CHAIN.replace("\"name\":\"Research\"", "\"name\":\"" + name + "\"");
    }

    private String createWorkflow(String body) throws Exception {
        String json = mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    // --- authoring the graph ---------------------------------------------

    @Test
    void aWorkflowIsCreatedWithItsGraphAndInfersTheEntryNode() throws Exception {
        String id = createWorkflow(CHAIN);

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Research")))
                .andExpect(jsonPath("$.entryNodeId", is("a")))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.edges", hasSize(2)));

        mvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(id)));
    }

    @Test
    void savingAWorkflowReplacesItsWholeGraph() throws Exception {
        String id = createWorkflow(CHAIN);

        mvc.perform(put("/api/workflows/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Research v2",
                                 "nodes":[{"id":"a","name":"Collect","prompt":"Find sources"},
                                          {"id":"z","name":"Verdict","prompt":"Decide"}],
                                 "edges":[{"from":"a","to":"z"}],"entryNodeId":"a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Research v2")))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.edges", hasSize(1)));

        // The dropped nodes are really gone, not merged with the new set.
        mvc.perform(get("/api/workflows/" + id))
                .andExpect(jsonPath("$.nodes[?(@.id=='b')]", hasSize(0)));
    }

    @Test
    void twoWorkflowsMayUseTheSameNodeIdsWithoutClobberingEachOther() throws Exception {
        // Node ids are chosen by the client and only have to be unique WITHIN a
        // workflow, so two workflows naming their nodes "a"/"b" is legal. When the
        // client id was also the table's primary key, saving the second workflow
        // silently reassigned the first one's node rows to it — leaving a workflow
        // with edges and no nodes.
        String first = createWorkflow("""
                {"name":"First",
                 "nodes":[{"id":"a","name":"First A","prompt":"x"},{"id":"b","name":"First B","prompt":"y"}],
                 "edges":[{"from":"a","to":"b"}]}
                """);
        String second = createWorkflow("""
                {"name":"Second",
                 "nodes":[{"id":"a","name":"Second A","prompt":"x"},{"id":"b","name":"Second B","prompt":"y"}],
                 "edges":[{"from":"a","to":"b"}]}
                """);

        mvc.perform(get("/api/workflows/" + first))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].name", is(java.util.List.of("First A"))));
        mvc.perform(get("/api/workflows/" + second))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].name", is(java.util.List.of("Second A"))));

        // And deleting one must not take the other's nodes with it.
        mvc.perform(delete("/api/workflows/" + second)).andExpect(status().isNoContent());
        mvc.perform(get("/api/workflows/" + first)).andExpect(jsonPath("$.nodes", hasSize(2)));
    }

    @Test
    void reSavingAWorkflowKeepsItsNodeIdsUsable() throws Exception {
        // A save deletes the old rows and writes the new ones in one transaction, so
        // reusing the same ids must not trip the (workflowId, nodeId) uniqueness.
        String id = createWorkflow(CHAIN);

        mvc.perform(put("/api/workflows/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Research","nodes":[{"id":"a","name":"Collect again","prompt":"p"},
                                          {"id":"b","name":"Summarise","prompt":"q"},
                                          {"id":"c","name":"Publish","prompt":"r"}],
                                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}],"entryNodeId":"a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].name", is(java.util.List.of("Collect again"))));
    }

    @Test
    void aCyclicGraphIsRejected() throws Exception {
        mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Loop",
                                 "nodes":[{"id":"a","name":"A","prompt":"x"},{"id":"b","name":"B","prompt":"y"}],
                                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}],"entryNodeId":"a"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("cycle")));
    }

    @Test
    void aGraphWithNoSingleEntryIsRejectedWhenTheClientDoesNotChooseOne() throws Exception {
        // Two roots and no entryNodeId: the server must not guess which branch to run.
        mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Two roots",
                                 "nodes":[{"id":"a","name":"A","prompt":"x"},
                                          {"id":"b","name":"B","prompt":"y"},
                                          {"id":"c","name":"C","prompt":"z"}],
                                 "edges":[{"from":"a","to":"c"},{"from":"b","to":"c"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("exactly one entry node")));
    }

    @Test
    void anEdgeToAnUnknownNodeIsRejected() throws Exception {
        mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dangling",
                                 "nodes":[{"id":"a","name":"A","prompt":"x"}],
                                 "edges":[{"from":"a","to":"ghost"}],"entryNodeId":"a"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWorkflowWithoutNodesIsRejected() throws Exception {
        mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Empty\",\"nodes\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // --- duplicating ------------------------------------------------------

    @Test
    void duplicatingAWorkflowCopiesItsWholeGraphIntoANewOne() throws Exception {
        String id = createWorkflow(chainNamed("Dup graph"));

        String json = mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Dup graph (copy)")))
                .andExpect(jsonPath("$.description", is("three steps")))
                .andExpect(jsonPath("$.entryNodeId", is("a")))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.edges", hasSize(2)))
                // Prompts come along — retyping them is the whole reason to duplicate.
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].prompt", is(java.util.List.of("Find sources"))))
                .andReturn().getResponse().getContentAsString();

        String copyId = mapper.readTree(json).get("id").asText();
        assertThat(copyId).isNotEqualTo(id);
        // Both are now on the list — the copy is a workflow, not a variant of one.
        mvc.perform(get("/api/workflows"))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.id=='" + copyId + "')]", hasSize(1)));
    }

    @Test
    void theCopyIsIndependentOfTheOriginal() throws Exception {
        // Node ids are reused verbatim (they only have to be unique within their own
        // workflow), so this is the case that would break if the copy's rows were
        // ever addressed by node id alone.
        String id = createWorkflow(chainNamed("Dup independence"));
        String copyId = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(put("/api/workflows/" + copyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rewritten copy",
                                 "nodes":[{"id":"a","name":"Only step","prompt":"changed"}],
                                 "edges":[],"entryNodeId":"a"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(jsonPath("$.name", is("Dup independence")))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].prompt", is(java.util.List.of("Find sources"))));

        // And deleting the original leaves the copy whole.
        mvc.perform(delete("/api/workflows/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/workflows/" + copyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(1)));
    }

    @Test
    void theCopyStartsWithNoRunHistory() throws Exception {
        String id = createWorkflow(chainNamed("Dup runs"));
        mvc.perform(post("/api/workflows/" + id + "/run")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated());

        String copyId = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // An execution is a record of something that happened; the copy never ran.
        mvc.perform(get("/api/workflows/executions?workflowId=" + copyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/workflows/executions?workflowId=" + id))
                .andExpect(jsonPath("$", hasSize(1)));

        // The copy is runnable in its own right — the graph came across valid.
        mvc.perform(post("/api/workflows/" + copyId + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowName", is("Dup runs (copy)")))
                .andExpect(jsonPath("$.totalNodes", is(3)));
    }

    @Test
    void copiesOfCopiesAreNumberedRatherThanNamedTheSame() throws Exception {
        String id = createWorkflow(chainNamed("Dup naming"));
        String first = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andExpect(jsonPath("$.name", is("Dup naming (copy)")))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andExpect(jsonPath("$.name", is("Dup naming (copy 2)")));
        // Duplicating the copy itself lands in the same series, not "(copy) (copy)".
        mvc.perform(post("/api/workflows/" + first + "/duplicate"))
                .andExpect(jsonPath("$.name", is("Dup naming (copy 3)")));
    }

    @Test
    void duplicatingAWorkflowThatIsNotThereAnswers404() throws Exception {
        mvc.perform(post("/api/workflows/nope/duplicate")).andExpect(status().isNotFound());
    }

    // --- executions -------------------------------------------------------

    @Test
    void runningAWorkflowQueuesOneNodeRunPerNodeInTopologicalOrder() throws Exception {
        String id = createWorkflow(CHAIN);

        String json = mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"gold prices\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.input", is("gold prices")))
                .andExpect(jsonPath("$.workflowName", is("Research")))
                .andExpect(jsonPath("$.totalNodes", is(3)))
                .andExpect(jsonPath("$.doneNodes", is(0)))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.nodes[0].nodeName", is("Collect")))
                .andExpect(jsonPath("$.nodes[1].nodeName", is("Summarise")))
                .andExpect(jsonPath("$.nodes[2].nodeName", is("Publish")))
                // Each run carries the dependencies it will wait on, snapshotted now.
                .andExpect(jsonPath("$.nodes[0].dependsOn", hasSize(0)))
                .andExpect(jsonPath("$.nodes[1].dependsOn[0]", is("a")))
                .andReturn().getResponse().getContentAsString();

        long executionId = mapper.readTree(json).get("id").asLong();
        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) executionId)));
        mvc.perform(get("/api/workflows/executions?workflowId=" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aNodeTheEntryCannotReachIsSkippedRatherThanLeftQueuedForever() throws Exception {
        String id = createWorkflow("""
                {"name":"With an orphan",
                 "nodes":[{"id":"a","name":"A","prompt":"x"},
                          {"id":"b","name":"B","prompt":"y"},
                          {"id":"orphan","name":"Orphan","prompt":"z"}],
                 "edges":[{"from":"a","to":"b"}],"entryNodeId":"a"}
                """);

        mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodes[?(@.nodeName=='Orphan')].status", is(java.util.List.of("SKIPPED"))));
    }

    @Test
    void aRunCanBeCancelledAndThenNoLonger() throws Exception {
        String id = createWorkflow(CHAIN);
        long executionId = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/workflows/executions/" + executionId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                // Nothing had started, so every node is closed out as skipped.
                .andExpect(jsonPath("$.nodes[0].status", is("SKIPPED")));

        mvc.perform(post("/api/workflows/executions/" + executionId + "/cancel"))
                .andExpect(status().isConflict());
    }

    @Test
    void anExecutionKeepsItsOwnRecordAfterTheWorkflowIsEdited() throws Exception {
        String id = createWorkflow(CHAIN);
        long executionId = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // Rewrite the workflow completely — a past run must still read as it ran.
        mvc.perform(put("/api/workflows/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Something else",
                                 "nodes":[{"id":"q","name":"Only node","prompt":"x"}],
                                 "edges":[],"entryNodeId":"q"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.workflowName", is("Research")))
                .andExpect(jsonPath("$.nodes", hasSize(3)))
                .andExpect(jsonPath("$.nodes[0].prompt", is("Find sources")));
    }

    // --- retrying a run that broke ---------------------------------------

    /** Start a run of {@code workflowId} and return its id. */
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
            run.setCostUsd(0.5);
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

    @Test
    void aRetryReRunsTheFailedStepAndWhatItBlocked() throws Exception {
        String id = createWorkflow(chainNamed("Retry chain"));
        long executionId = startRun(id);

        // "Collect" worked, "Summarise" timed out, "Publish" never got a chance.
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.FAILED, "Timed out after 3600s.");
        settle(executionId, "c", RunState.SKIPPED, "Skipped: the node it depends on did not succeed.");
        settleRun(executionId, RunState.FAILED);

        // The page reads the count off the run rather than working it out itself.
        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.retryableNodes", is(2)));

        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isOk())
                // Queued again, and the reason it stopped is no longer current.
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.error").doesNotExist())
                // The step that worked keeps its output — the point of retrying at all.
                .andExpect(jsonPath("$.nodes[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[0].output", is("three sources")))
                .andExpect(jsonPath("$.nodes[0].attempt", is(1)))
                // The failure and the step behind it are back in the queue, clean.
                .andExpect(jsonPath("$.nodes[1].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[1].error").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].attempt", is(2)))
                .andExpect(jsonPath("$.nodes[2].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[2].attempt", is(2)))
                // It replays the prompts the RUN snapshotted, not the workflow's today.
                .andExpect(jsonPath("$.nodes[1].prompt", is("Summarise the input")))
                .andExpect(jsonPath("$.nodes[1].dependsOn[0]", is("a")));
    }

    @Test
    void aRetryDoesNotStartAStepTheEntryNeverReached() throws Exception {
        // "orphan" is a node the user built but never wired in: it was SKIPPED the
        // moment the run was created. It has no dependencies, so a retry that reset
        // it would hand the runner a session nobody asked for.
        String id = createWorkflow("""
                {"name":"Retry with an orphan",
                 "nodes":[{"id":"a","name":"A","prompt":"x"},
                          {"id":"b","name":"B","prompt":"y"},
                          {"id":"orphan","name":"Orphan","prompt":"z"}],
                 "edges":[{"from":"a","to":"b"}],"entryNodeId":"a"}
                """);
        long executionId = startRun(id);

        settle(executionId, "a", RunState.FAILED, "Claude exited with code 1.");
        settle(executionId, "b", RunState.SKIPPED, "Skipped: the node it depends on did not succeed.");
        settleRun(executionId, RunState.FAILED);

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.retryableNodes", is(2)));

        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.nodeName=='A')].status", is(java.util.List.of("PENDING"))))
                .andExpect(jsonPath("$.nodes[?(@.nodeName=='B')].status", is(java.util.List.of("PENDING"))))
                .andExpect(jsonPath("$.nodes[?(@.nodeName=='Orphan')].status",
                        is(java.util.List.of("SKIPPED"))));
    }

    @Test
    void aCancelledRunCanBeStartedAgainFromWhereItStopped() throws Exception {
        String id = createWorkflow(chainNamed("Retry after cancel"));
        long executionId = startRun(id);

        mvc.perform(post("/api/workflows/executions/" + executionId + "/cancel"))
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.nodes[0].status", is("SKIPPED")))
                // Cancelling leaves three steps a retry would pick back up.
                .andExpect(jsonPath("$.retryableNodes", is(3)));

        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.nodes[0].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[2].status", is("PENDING")));
    }

    @Test
    void aRunWithNothingLeftToRetryIsRefusedRatherThanQueuedAgain() throws Exception {
        String id = createWorkflow(chainNamed("Retry nothing"));
        long executionId = startRun(id);

        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settle(executionId, "c", RunState.SUCCEEDED, "three");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.retryableNodes", is(0)));
        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("already succeeded"));
    }

    @Test
    void aRunThatIsStillGoingCannotBeRetriedUnderneathItself() throws Exception {
        String id = createWorkflow(chainNamed("Retry live"));
        long executionId = startRun(id);

        // Freshly queued, so still in flight. Resetting rows now would fight the runner.
        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.retryableNodes", is(0)));
        mvc.perform(post("/api/workflows/executions/" + executionId + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("cancel it"));
    }

    @Test
    void retryingARunThatIsNotThereAnswers404() throws Exception {
        mvc.perform(post("/api/workflows/executions/999999/retry")).andExpect(status().isNotFound());
    }

    // --- re-running one step the user picked ------------------------------

    private long nodeRunId(long executionId, String nodeId) {
        return nodeRuns.findByExecutionIdOrderByOrderIndexAsc(executionId).stream()
                .filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow().getId();
    }

    @Test
    void aStepThatSucceededCanBeReRunAndTakesEverythingBelowItWithIt() throws Exception {
        // Nothing failed here. "Summarise" produced a perfectly good answer to the
        // wrong question, which is the case a whole-run retry has no handle on.
        String id = createWorkflow(chainNamed("Re-run a good step"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "the wrong angle");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);

        // A finished run has nothing to retry, yet every step can be re-run by hand —
        // and each says how many steps that would redo.
        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.retryableNodes", is(0)))
                .andExpect(jsonPath("$.nodes[0].rerunFromCount", is(3)))
                .andExpect(jsonPath("$.nodes[1].rerunFromCount", is(2)))
                .andExpect(jsonPath("$.nodes[2].rerunFromCount", is(1)));

        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                // Untouched: its output is the input the redone step is handed.
                .andExpect(jsonPath("$.nodes[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[0].output", is("three sources")))
                .andExpect(jsonPath("$.nodes[0].attempt", is(1)))
                // The picked step, cleared for a second attempt.
                .andExpect(jsonPath("$.nodes[1].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[1].output").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].attempt", is(2)))
                // It read the old output, so its answer is no longer about anything.
                .andExpect(jsonPath("$.nodes[2].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[2].output").doesNotExist())
                .andExpect(jsonPath("$.nodes[2].attempt", is(2)))
                // The bill for the first attempt stands; it was really spent.
                .andExpect(jsonPath("$.nodes[1].costUsd", is(0.5)));
    }

    @Test
    void reRunningTheLastStepLeavesEveryOtherStepAlone() throws Exception {
        String id = createWorkflow(chainNamed("Re-run the last step"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settle(executionId, "c", RunState.SUCCEEDED, "three");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "c") + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[1].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[1].output", is("two")))
                .andExpect(jsonPath("$.nodes[2].status", is("PENDING")));
    }

    @Test
    void aStepTheEntryNeverReachedCannotBeStartedByHand() throws Exception {
        String id = createWorkflow("""
                {"name":"Re-run with an orphan",
                 "nodes":[{"id":"a","name":"A","prompt":"x"},
                          {"id":"b","name":"B","prompt":"y"},
                          {"id":"orphan","name":"Orphan","prompt":"z"}],
                 "edges":[{"from":"a","to":"b"}],"entryNodeId":"a"}
                """);
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settleRun(executionId, RunState.SUCCEEDED);

        // The button isn't offered for it, and the service doesn't rely on that.
        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.nodes[?(@.nodeName=='Orphan')].rerunFromCount",
                        is(java.util.List.of(0))));
        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "orphan") + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("isn't wired in"));
    }

    @Test
    void noStepOfALiveRunCanBeReRunUnderneathTheRunner() throws Exception {
        String id = createWorkflow(chainNamed("Re-run live"));
        long executionId = startRun(id);

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.nodes[0].rerunFromCount", is(0)));
        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "a") + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("still going"));
    }

    @Test
    void aStepCanBeReRunOnItsOwnLeavingTheStepsBelowItWithWhatTheyProduced() throws Exception {
        // The choice the card is about. Same click as above, one query parameter apart,
        // and the difference is eight sessions on a nine-step chain.
        String id = createWorkflow(chainNamed("Re-run just one step"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "the wrong angle");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.nodes[1].rerunFromCount", is(2)))
                .andExpect(jsonPath("$.nodes[1].canRerunAlone", is(true)));

        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry")
                        .param("downstream", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                // Above it: untouched, as with the cascade — its output is the input.
                .andExpect(jsonPath("$.nodes[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[0].output", is("three sources")))
                // The picked step, and only it, cleared for a second attempt.
                .andExpect(jsonPath("$.nodes[1].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[1].output").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].attempt", is(2)))
                // Below it: kept, which is the whole difference from the cascade. What
                // it says is now an answer to an earlier version of its input, which is
                // what the page labels rather than what the API discards.
                .andExpect(jsonPath("$.nodes[2].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[2].output", is("published")))
                .andExpect(jsonPath("$.nodes[2].attempt", is(1)));
    }

    @Test
    void aStepWithNoInputWaitingForItCannotBeReRunOnItsOwn() throws Exception {
        // "a" failed, so "b" was skipped and there is no stored output to hand it. On
        // its own the runner would skip the reset row straight away, having thrown its
        // previous state away for nothing — so the API refuses and names the way out.
        String id = createWorkflow(chainNamed("Re-run alone with no input"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.FAILED, null);
        settle(executionId, "b", RunState.SKIPPED, null);
        settle(executionId, "c", RunState.SKIPPED, null);
        settleRun(executionId, RunState.FAILED);

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(jsonPath("$.nodes[1].rerunFromCount", is(2)))
                .andExpect(jsonPath("$.nodes[1].canRerunAlone", is(false)));

        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry")
                        .param("downstream", "false"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("no input waiting for it"));

        // The cascade from the same step is still on offer: it resets "a" as well, and
        // that is what produces the input.
        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry")
                        .param("downstream", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void reRunningAStepTakesEverythingBelowItUnlessToldOtherwise() throws Exception {
        // The default is the cautious one, so an older client that doesn't know about
        // the parameter keeps getting the behaviour it was written against.
        String id = createWorkflow(chainNamed("Re-run default"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settle(executionId, "c", RunState.SUCCEEDED, "three");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[2].status", is("PENDING")));
    }

    @Test
    void reRunningAStepThatIsNotThereAnswers404() throws Exception {
        mvc.perform(post("/api/workflows/node-runs/999999/retry")).andExpect(status().isNotFound());
    }

    // --- editing a step's prompt inside one run ---------------------------

    private static String promptBody(String prompt) {
        return "{\"prompt\":\"" + prompt + "\"}";
    }

    @Test
    void aStepsPromptCanBeRewrittenInTheRunAndTheRewriteIsWhatTheReRunUses() throws Exception {
        String id = createWorkflow(chainNamed("Edit a prompt"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "three sources");
        settle(executionId, "b", RunState.SUCCEEDED, "the wrong angle");
        settle(executionId, "c", RunState.SUCCEEDED, "published");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/prompt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(promptBody("Summarise the input in one paragraph")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].prompt", is("Summarise the input in one paragraph")))
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").exists())
                // Only that step. The others are still the workflow's text.
                .andExpect(jsonPath("$.nodes[0].prompt", is("Find sources")))
                .andExpect(jsonPath("$.nodes[0].settingsEditedAt").doesNotExist())
                // Nothing has been re-run, so the answer on the row is still the old
                // prompt's — the edit alone must not disturb the record.
                .andExpect(jsonPath("$.nodes[1].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.nodes[1].output", is("the wrong angle")));

        // The workflow itself is untouched: an experiment, not a change of policy.
        mvc.perform(get("/api/workflows/" + id))
                .andExpect(jsonPath("$.nodes[1].prompt", is("Summarise the input")));

        // And the re-run carries the new text into the next attempt — which is the
        // whole point of editing it here rather than in the editor.
        mvc.perform(post("/api/workflows/node-runs/" + nodeRunId(executionId, "b") + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[1].status", is("PENDING")))
                .andExpect(jsonPath("$.nodes[1].attempt", is(2)))
                .andExpect(jsonPath("$.nodes[1].prompt", is("Summarise the input in one paragraph")))
                .andExpect(jsonPath("$.nodes[1].settingsEditedAt").exists());
    }

    @Test
    void savingTheSameTextIsNotAnEdit() throws Exception {
        // Otherwise opening the box and closing it again would mark the step's output
        // as produced by an older prompt, which is exactly what the stamp denies.
        String id = createWorkflow(chainNamed("Edit nothing"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "a") + "/prompt")
                        .contentType(MediaType.APPLICATION_JSON).content(promptBody("Find sources")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].prompt", is("Find sources")))
                .andExpect(jsonPath("$.nodes[0].settingsEditedAt").doesNotExist());
    }

    @Test
    void noPromptOfALiveRunCanBeEditedUnderneathTheRunner() throws Exception {
        String id = createWorkflow(chainNamed("Edit live"));
        long executionId = startRun(id);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "a") + "/prompt")
                        .contentType(MediaType.APPLICATION_JSON).content(promptBody("Something else")))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("still going"));
    }

    @Test
    void thePromptOfAStepTheEntryNeverReachedCannotBeEdited() throws Exception {
        // Editable exactly where re-runnable: on a step that can never run, the edit
        // would change the record of what this run did and never be tested.
        String id = createWorkflow("""
                {"name":"Edit an orphan",
                 "nodes":[{"id":"a","name":"A","prompt":"x"},
                          {"id":"b","name":"B","prompt":"y"},
                          {"id":"orphan","name":"Orphan","prompt":"z"}],
                 "edges":[{"from":"a","to":"b"}],"entryNodeId":"a"}
                """);
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settle(executionId, "b", RunState.SUCCEEDED, "two");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "orphan") + "/prompt")
                        .contentType(MediaType.APPLICATION_JSON).content(promptBody("z2")))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("isn't wired in"));
    }

    @Test
    void aPromptTooLongForTheEditorIsRefusedHereToo() throws Exception {
        String id = createWorkflow(chainNamed("Edit too long"));
        long executionId = startRun(id);
        settle(executionId, "a", RunState.SUCCEEDED, "one");
        settleRun(executionId, RunState.SUCCEEDED);

        mvc.perform(put("/api/workflows/node-runs/" + nodeRunId(executionId, "a") + "/prompt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(promptBody("x".repeat(20_001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editingThePromptOfAStepThatIsNotThereAnswers404() throws Exception {
        mvc.perform(put("/api/workflows/node-runs/999999/prompt")
                        .contentType(MediaType.APPLICATION_JSON).content(promptBody("hi")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAWorkflowTakesItsRunsWithIt() throws Exception {
        String id = createWorkflow(CHAIN);
        long executionId = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(delete("/api/workflows/" + id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/workflows/" + id)).andExpect(status().isNotFound());
        // An execution with no workflow behind it would sit on the page unopenable.
        mvc.perform(get("/api/workflows/executions/" + executionId)).andExpect(status().isNotFound());
    }

    @Test
    void aNodeRunExposesItsLiveLogEndpoint() throws Exception {
        String id = createWorkflow(CHAIN);
        JsonNode execution = mapper.readTree(mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString());
        long nodeRunId = execution.get("nodes").get(0).get("id").asLong();

        mvc.perform(get("/api/workflows/node-runs/" + nodeRunId + "/log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.log", is("")));
    }

    @Test
    void unknownWorkflowsAndExecutionsAnswer404() throws Exception {
        mvc.perform(get("/api/workflows/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/workflows/executions/9999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/workflows/nope/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // --- a step's own effort level ---------------------------------------

    /** What a step with no level of its own reads as: present, and null. */
    private static final java.util.List<String> DEFAULT_EFFORT =
            java.util.Collections.singletonList(null);

    @Test
    void aStepsEffortLevelIsSavedAndReadBackWhileTheOthersStayOnTheDefault() throws Exception {
        String id = createWorkflow("""
                {"name":"Mixed effort",
                 "nodes":[{"id":"a","name":"Reason it out","prompt":"x","effort":"max"},
                          {"id":"b","name":"Tidy the wording","prompt":"y","effort":"low"},
                          {"id":"c","name":"Whatever the CLI does","prompt":"z"}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}
                """);

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].effort", is(java.util.List.of("max"))))
                .andExpect(jsonPath("$.nodes[?(@.id=='b')].effort", is(java.util.List.of("low"))))
                // Null, not "medium": the step is left to the CLI's own default, and
                // naming a level here would pin it to today's default forever.
                .andExpect(jsonPath("$.nodes[?(@.id=='c')].effort", is(DEFAULT_EFFORT)));
    }

    @Test
    void aLevelThatIsNotOneOfTheListedOnesIsRefusedInsteadOfSilentlyIgnored() throws Exception {
        // The CLI's own handling of a bad --effort value is a warning on stderr and a
        // run at the default, which nothing on the page would ever show. This 400 is
        // the only place the mistake is visible.
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Bad level",
                         "nodes":[{"id":"a","name":"Reason it out","prompt":"x","effort":"extreme"}],
                         "edges":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("low, medium, high, xhigh, max")))
                .andExpect(status().reason(containsString("Reason it out")));
    }

    @Test
    void aStepCanBeSavedOnUltracodeAndComesBackOnIt() throws Exception {
        // The level the picker was missing. It goes the whole way through — parsed on
        // save, stored on the node, served back — because a level that round-trips as
        // null would leave the step running at the default with the page showing it set.
        String id = createWorkflow("""
                {"name":"Ultracode",
                 "nodes":[{"id":"a","name":"Dig","prompt":"x","effort":"ultracode"},
                          {"id":"b","name":"Tidy","prompt":"y","effort":"low"}],
                 "edges":[{"from":"a","to":"b"}]}
                """);

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].effort", is(java.util.List.of("ultracode"))))
                .andExpect(jsonPath("$.nodes[?(@.id=='b')].effort", is(java.util.List.of("low"))));
    }

    @Test
    void aLevelIsTakenInAnyCasingAndAnEmptyOneMeansTheDefault() throws Exception {
        String id = createWorkflow("""
                {"name":"Casing",
                 "nodes":[{"id":"a","name":"A","prompt":"x","effort":"XHigh"},
                          {"id":"b","name":"B","prompt":"y","effort":""}],
                 "edges":[{"from":"a","to":"b"}]}
                """);

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].effort", is(java.util.List.of("xhigh"))))
                .andExpect(jsonPath("$.nodes[?(@.id=='b')].effort", is(DEFAULT_EFFORT)));
    }

    @Test
    void aRunSnapshotsTheLevelEachStepWasAuthoredWithAndKeepsItWhenTheWorkflowChanges()
            throws Exception {
        String id = createWorkflow("""
                {"name":"Snapshot effort",
                 "nodes":[{"id":"a","name":"A","prompt":"x","effort":"max"},
                          {"id":"b","name":"B","prompt":"y"}],
                 "edges":[{"from":"a","to":"b"}]}
                """);
        String run = mvc.perform(post("/api/workflows/" + id + "/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"go\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long executionId = mapper.readTree(run).get("id").asLong();

        // Re-author the whole graph downward. The run already made its copy.
        mvc.perform(put("/api/workflows/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Snapshot effort",
                         "nodes":[{"id":"a","name":"A","prompt":"x","effort":"low"},
                                  {"id":"b","name":"B","prompt":"y","effort":"low"}],
                         "edges":[{"from":"a","to":"b"}]}
                        """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/workflows/executions/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.nodeId=='a')].effort", is(java.util.List.of("max"))))
                .andExpect(jsonPath("$.nodes[?(@.nodeId=='b')].effort", is(DEFAULT_EFFORT)));
    }

    @Test
    void duplicatingAWorkflowCarriesEachStepsLevelAcross() throws Exception {
        String id = createWorkflow("""
                {"name":"Effort original",
                 "nodes":[{"id":"a","name":"A","prompt":"x","effort":"high"},
                          {"id":"b","name":"B","prompt":"y"}],
                 "edges":[{"from":"a","to":"b"}]}
                """);

        mvc.perform(post("/api/workflows/" + id + "/duplicate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodes[?(@.id=='a')].effort", is(java.util.List.of("high"))))
                .andExpect(jsonPath("$.nodes[?(@.id=='b')].effort", is(DEFAULT_EFFORT)));
    }
}
