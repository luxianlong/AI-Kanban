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

    @Test
    void suggestDecomposesGoalIntoOrderedSteps() throws Exception {
        // A feature goal with backend + frontend hints yields a design → backend →
        // UI → tests → docs chain, and the steps are ready to POST straight back.
        mvc.perform(post("/api/stories/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"Add an API endpoint and a UI page for exporting todos\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Add an API endpoint and a UI page for exporting todos")))
                .andExpect(jsonPath("$.steps.length()", is(5)))
                .andExpect(jsonPath("$.steps[0].title", is("Design and plan the change")))
                .andExpect(jsonPath("$.steps[1].title", is("Implement the backend")))
                .andExpect(jsonPath("$.steps[2].title", is("Build the UI")))
                .andExpect(jsonPath("$.steps[3].title", is("Add tests")))
                .andExpect(jsonPath("$.steps[4].title", is("Update documentation")));
    }

    @Test
    void suggestRecognisesBugFixes() throws Exception {
        mvc.perform(post("/api/stories/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"Fix the crash when deleting a running task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()", is(3)))
                .andExpect(jsonPath("$.steps[0].title", is("Reproduce and diagnose the problem")))
                .andExpect(jsonPath("$.steps[1].title", is("Implement the fix")))
                .andExpect(jsonPath("$.steps[2].title", is("Add a regression test")));
    }

    @Test
    void suggestRejectsBlankGoal() throws Exception {
        mvc.perform(post("/api/stories/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relatedFindsMatchingStoryAndIgnoresUnrelatedText() throws Exception {
        // Unique keywords ("avatar"/"gallery") keep this isolated from the shared
        // in-memory DB that other tests also write stories into.
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Redesign avatar gallery\",\"steps\":["
                                + "{\"title\":\"Add avatar upload\"},"
                                + "{\"title\":\"Lay out gallery grid\"}]}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/stories/related").param("text", "improve the avatar gallery grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].name", is("Redesign avatar gallery")))
                .andExpect(jsonPath("$[0].score", org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/stories/related").param("text", "quarterly zebra picnic umbrella"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void addStepAppendsToStoryChain() throws Exception {
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ship search\",\"steps\":["
                                + "{\"title\":\"Add search box\"},"
                                + "{\"title\":\"Filter results\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storyId = mapper.readTree(body).get(0).get("storyId").asText();

        // The appended task lands as the next step (order 2) on the same chain.
        mvc.perform(post("/api/stories/" + storyId + "/steps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Highlight matches\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Highlight matches")))
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Ship search")))
                .andExpect(jsonPath("$.storyOrder", is(2)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].steps.length()", is(3)))
                .andExpect(jsonPath("$[0].steps[2].title", is("Highlight matches")));
    }

    @Test
    void newTodoIsClassifiedIntoRelatedStory() throws Exception {
        // A declared story about a distinctive subject...
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Build kayak rental checkout\",\"steps\":["
                                + "{\"title\":\"Add kayak inventory model\"},"
                                + "{\"title\":\"Build kayak checkout page\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storyId = mapper.readTree(body).get(0).get("storyId").asText();

        // ...means a newly created task whose words clearly overlap it (kayak,
        // checkout) is auto-filed onto that chain as the next step, not orphaned.
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Refine the kayak checkout flow\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Build kayak rental checkout")))
                .andExpect(jsonPath("$.storyOrder", is(2)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        // The classified task shows up as the appended step of the chain.
        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Build kayak rental checkout")))
                .andExpect(jsonPath("$[0].steps.length()", is(3)))
                .andExpect(jsonPath("$[0].steps[2].title", is("Refine the kayak checkout flow")));
    }

    @Test
    void newTodoWithNoStoryOverlapStaysStandalone() throws Exception {
        // Stories exist, but a task about something entirely different is left
        // unclassified rather than force-fitted into an unrelated chain.
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tune the caching layer\",\"steps\":["
                                + "{\"title\":\"Add a cache warmer\"}]}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Document the quarterly zebra picnic\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId", is(nullValue())))
                .andExpect(jsonPath("$.storyName", is(nullValue())))
                .andExpect(jsonPath("$.storyOrder", is(nullValue())));
    }

    @Test
    void singleSharedWordIsNotEnoughToClassify() throws Exception {
        // "flow" alone overlaps this story, but one incidental word is below the
        // auto-classify threshold, so the task stays standalone.
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Harden authentication flow\",\"steps\":["
                                + "{\"title\":\"Rotate the auth tokens\"},"
                                + "{\"title\":\"Add auth rate limiting\"}]}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Design the onboarding flow\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId", is(nullValue())))
                .andExpect(jsonPath("$.storyName", is(nullValue())));
    }

    @Test
    void addStepToMissingStoryReturns404() throws Exception {
        mvc.perform(post("/api/stories/does-not-exist/steps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Orphan\"}"))
                .andExpect(status().isNotFound());
    }
}
