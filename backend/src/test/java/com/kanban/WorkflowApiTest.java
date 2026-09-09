package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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

    private final ObjectMapper mapper = new ObjectMapper();

    /** A → B → C, entry inferred from A being the only node with no input. */
    private static final String CHAIN = """
            {"name":"Research","description":"three steps",
             "nodes":[{"id":"a","name":"Collect","prompt":"Find sources"},
                      {"id":"b","name":"Summarise","prompt":"Summarise the input"},
                      {"id":"c","name":"Publish","prompt":"Write it up"}],
             "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}
            """;

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
}
