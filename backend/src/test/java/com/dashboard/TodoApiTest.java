package com.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end API test with the Claude worker disabled (no CLI launched). */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
})
class TodoApiTest {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Creates a todo and returns its generated id. */
    private long createTodo(String title) throws Exception {
        String body = mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test
    void createThenListTodo() throws Exception {
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Write a haiku\",\"description\":\"about spring\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Write a haiku")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                // Cost/duration are part of the schema but unset until an agent run reports them.
                .andExpect(jsonPath("$.costUsd", is(nullValue())))
                .andExpect(jsonPath("$.durationMs", is(nullValue())));

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title", is("Write a haiku")))
                .andExpect(jsonPath("$[0].costUsd", is(nullValue())))
                .andExpect(jsonPath("$[0].durationMs", is(nullValue())));
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204ThenGoneOnFollowup() throws Exception {
        long id = createTodo("Delete me");

        mvc.perform(delete("/api/todos/" + id))
                .andExpect(status().isNoContent());

        // The item is gone: fetching or deleting it again is a 404.
        mvc.perform(get("/api/todos/" + id))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/todos/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchWithUnknownStatusReturns400() throws Exception {
        long id = createTodo("Advance me");

        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOnMissingIdReturns404() throws Exception {
        mvc.perform(get("/api/todos/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchOnMissingIdReturns404() throws Exception {
        mvc.perform(patch("/api/todos/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchAdvancesStatusAndStoresResult() throws Exception {
        long id = createTodo("Resolve me");

        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\",\"result\":\"all done\"}"))
                .andExpect(status().isOk())
                // valueOf is applied to a trimmed, upper-cased value, so lowercase works.
                .andExpect(jsonPath("$.status", is("RESOLVED")))
                .andExpect(jsonPath("$.result", is("all done")));
    }

    @Test
    void logEndpointReturnsIdStatusAndLog() throws Exception {
        long id = createTodo("Show my log");

        mvc.perform(get("/api/todos/" + id + "/log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) id)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                // No session has run yet, so the live log is empty rather than absent.
                .andExpect(jsonPath("$.log", is("")));
    }

    @Test
    void logEndpointOnMissingIdReturns404() throws Exception {
        mvc.perform(get("/api/todos/999999/log"))
                .andExpect(status().isNotFound());
    }

    @Test
    void plainTodoHasNullStoryFields() throws Exception {
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Standalone\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId", is(nullValue())))
                .andExpect(jsonPath("$.storyName", is(nullValue())))
                .andExpect(jsonPath("$.storyOrder", is(nullValue())));
    }

    @Test
    void createStoryChainsTasksInOrder() throws Exception {
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ship dark mode\",\"steps\":["
                                + "{\"title\":\"Add theme toggle\"},"
                                + "{\"title\":\"Persist preference\"},"
                                + "{\"title\":\"Write tests\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()", is(3)))
                // Steps share a storyId and are numbered in order, all PENDING.
                .andExpect(jsonPath("$[0].title", is("Add theme toggle")))
                .andExpect(jsonPath("$[0].storyName", is("Ship dark mode")))
                .andExpect(jsonPath("$[0].storyOrder", is(0)))
                .andExpect(jsonPath("$[0].status", is("PENDING")))
                .andExpect(jsonPath("$[1].storyOrder", is(1)))
                .andExpect(jsonPath("$[2].storyOrder", is(2)))
                .andReturn().getResponse().getContentAsString();

        JsonNode steps = mapper.readTree(body);
        String storyId = steps.get(0).get("storyId").asText();
        // Every step carries the same non-blank storyId.
        for (JsonNode step : steps) {
            org.junit.jupiter.api.Assertions.assertEquals(storyId, step.get("storyId").asText());
        }
        org.junit.jupiter.api.Assertions.assertFalse(storyId.isBlank());

        // The story is listable with its steps in chain order.
        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Ship dark mode")))
                .andExpect(jsonPath("$[0].steps.length()", is(3)))
                .andExpect(jsonPath("$[0].steps[0].storyOrder", is(0)))
                .andExpect(jsonPath("$[0].steps[2].storyOrder", is(2)));
    }

    @Test
    void createStoryWithNoStepsReturns400() throws Exception {
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Empty\",\"steps\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createStoryWithBlankStepTitleReturns400() throws Exception {
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"steps\":[{\"title\":\"\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
