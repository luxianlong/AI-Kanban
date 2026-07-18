package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.todo.LegacyStatusMigration;
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

    @Autowired
    LegacyStatusMigration migration;

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

    /** Creates a story from a one-line goal and returns its generated storyId. */
    private String createStory(String name) throws Exception {
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("storyId").asText();
    }

    @Test
    void createThenListTodo() throws Exception {
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Write a haiku\",\"description\":\"about spring\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Write a haiku")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                // A plain task is a general task, not aimed at the project itself.
                .andExpect(jsonPath("$.projectImprovement", is(false)))
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
    void projectImprovementFlagIsStoredAndReturned() throws Exception {
        // Explicitly opting in marks the task as a project-improvement task.
        long id = mapper.readTree(mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Improve the board\",\"projectImprovement\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectImprovement", is(true)))
                .andReturn().getResponse().getContentAsString())
                .get("id").asLong();

        mvc.perform(get("/api/todos/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectImprovement", is(true)));
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

    /** Creates a story and returns [plannerTodoId, storyId]. */
    private String[] createStoryReturningIds(String name) throws Exception {
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode planner = mapper.readTree(body);
        return new String[] { planner.get("id").asText(), planner.get("storyId").asText() };
    }

    /** The story with the given id from GET /api/stories, or null if absent. */
    private JsonNode findStory(String storyId) throws Exception {
        String body = mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : mapper.readTree(body)) {
            if (storyId.equals(s.path("storyId").asText())) {
                return s;
            }
        }
        return null;
    }

    @Test
    void emptyStoryStaysListedAfterItsTasksAreDeleted() throws Exception {
        // A distinctive name keeps this isolated from the shared in-memory DB.
        String[] ids = createStoryReturningIds("Persistent tangerine story");
        long plannerId = Long.parseLong(ids[0]);
        String storyId = ids[1];

        // Remove the story's only task (its planner seed).
        mvc.perform(delete("/api/todos/" + plannerId))
                .andExpect(status().isNoContent());

        // The story itself must still exist — now with zero steps — because its
        // Story row outlives the tasks. This is the core of task #279.
        JsonNode story = findStory(storyId);
        org.junit.jupiter.api.Assertions.assertNotNull(story, "empty story should still be listed");
        org.junit.jupiter.api.Assertions.assertEquals("Persistent tangerine story", story.get("name").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, story.get("steps").size());
    }

    @Test
    void canFileTaskUnderAnEmptyStory() throws Exception {
        String[] ids = createStoryReturningIds("Empty walnut story");
        long plannerId = Long.parseLong(ids[0]);
        String storyId = ids[1];
        mvc.perform(delete("/api/todos/" + plannerId)).andExpect(status().isNoContent());

        // Filing a standalone task under the (now empty) story must still work —
        // the story exists as a Story row even with no tasks.
        long taskId = createTodo("Some unrelated loose task");
        mvc.perform(patch("/api/todos/" + taskId + "/story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":\"" + storyId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Empty walnut story")))
                .andExpect(jsonPath("$.storyOrder", is(0)));
    }

    @Test
    void deleteStoryRemovesItAndItsTasks() throws Exception {
        String[] ids = createStoryReturningIds("Disposable mango story");
        long plannerId = Long.parseLong(ids[0]);
        String storyId = ids[1];
        String stepBody = mvc.perform(post("/api/stories/" + storyId + "/steps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a step\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long stepId = mapper.readTree(stepBody).get("id").asLong();

        mvc.perform(delete("/api/stories/" + storyId))
                .andExpect(status().isNoContent());

        // Story gone from the listing, and both its tasks are gone too.
        org.junit.jupiter.api.Assertions.assertNull(findStory(storyId));
        mvc.perform(get("/api/todos/" + plannerId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/todos/" + stepId)).andExpect(status().isNotFound());

        // Deleting it again is a 404.
        mvc.perform(delete("/api/stories/" + storyId)).andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownStoryReturns404() throws Exception {
        mvc.perform(delete("/api/stories/no-such-story"))
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
    void finishingATaskMarksItUnreadAndReadingItClearsIt() throws Exception {
        long id = createTodo("Notify me");
        // A fresh task is not an unread notification.
        mvc.perform(get("/api/todos/" + id))
                .andExpect(jsonPath("$.unread", is(false)));

        // Crossing into a terminal state flags it unread (the notification).
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread", is(true)));

        // Opening it (the read endpoint) clears the flag, and it stays cleared.
        mvc.perform(post("/api/todos/" + id + "/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread", is(false)));
        mvc.perform(get("/api/todos/" + id))
                .andExpect(jsonPath("$.unread", is(false)));
    }

    @Test
    void requeuingAFinishedTaskClearsUnreadUntilItFinishesAgain() throws Exception {
        long id = createTodo("Retry me");
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\"}"))
                .andExpect(jsonPath("$.unread", is(true)));

        // Requeuing (back to a non-terminal state) drops it out of the unread list.
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(jsonPath("$.unread", is(false)));

        // Finishing anew flags it unread again — a retried task re-notifies once.
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(jsonPath("$.unread", is(true)));
    }

    @Test
    void markAllReadClearsEveryUnreadNotification() throws Exception {
        long a = createTodo("First");
        long b = createTodo("Second");
        for (long id : new long[] {a, b}) {
            mvc.perform(patch("/api/todos/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(jsonPath("$.unread", is(true)));
        }

        mvc.perform(post("/api/todos/read-all"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/todos/" + a)).andExpect(jsonPath("$.unread", is(false)));
        mvc.perform(get("/api/todos/" + b)).andExpect(jsonPath("$.unread", is(false)));
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
    void stopOnMissingIdReturns404() throws Exception {
        mvc.perform(post("/api/todos/999999/stop"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopOnNonRunningTaskReturns409() throws Exception {
        // A freshly-created task is PENDING, not running — there is no session to
        // stop, so the endpoint rejects it with a conflict rather than pretending.
        long id = createTodo("Not running yet");

        mvc.perform(post("/api/todos/" + id + "/stop"))
                .andExpect(status().isConflict());

        // Resolved tasks aren't running either.
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/todos/" + id + "/stop"))
                .andExpect(status().isConflict());
    }

    @Test
    void stopOnInProgressTaskWithNoLiveSessionReturns409() throws Exception {
        // IN_PROGRESS but with no live process (e.g. orphaned by a restart): there
        // is genuinely nothing to kill, so it's a conflict, not a false success.
        long id = createTodo("Orphaned in progress");
        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/todos/" + id + "/stop"))
                .andExpect(status().isConflict());
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
    void createStoryFilesPlannerSeed() throws Exception {
        // Creating a story from a one-line goal files a single PLANNER seed — not
        // pre-written steps. It's queued (PENDING) so the worker plans it; it has
        // no storyOrder (it isn't a numbered step) and carries storyPlanner=true.
        String body = mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ship dark mode\",\"description\":\"support a dark theme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Ship dark mode")))
                .andExpect(jsonPath("$.storyName", is("Ship dark mode")))
                .andExpect(jsonPath("$.storyPlanner", is(true)))
                .andExpect(jsonPath("$.storyOrder", is(nullValue())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andReturn().getResponse().getContentAsString();

        JsonNode planner = mapper.readTree(body);
        String storyId = planner.get("storyId").asText();
        org.junit.jupiter.api.Assertions.assertFalse(storyId.isBlank());

        // The story is listable, containing its planner seed.
        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Ship dark mode")))
                .andExpect(jsonPath("$[0].steps.length()", is(1)))
                .andExpect(jsonPath("$[0].steps[0].storyPlanner", is(true)));
    }

    @Test
    void createStoryWithBlankNameReturns400() throws Exception {
        mvc.perform(post("/api/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
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
        createStory("Redesign avatar gallery");

        mvc.perform(get("/api/stories/related").param("text", "improve the avatar gallery layout"))
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
        // A fresh story has only its planner (null order), so the first appended
        // step lands at order 0, queued (PENDING) to run in chain order.
        String storyId = createStory("Ship search feature");

        mvc.perform(post("/api/stories/" + storyId + "/steps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Highlight matches\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Highlight matches")))
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Ship search feature")))
                .andExpect(jsonPath("$.storyOrder", is(0)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        // The story now lists its planner plus the appended step.
        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].steps.length()", is(2)));
    }

    @Test
    void newTodoIsClassifiedIntoRelatedStory() throws Exception {
        // A declared story about a distinctive subject (its goal alone carries the
        // keywords kayak/checkout)...
        String storyId = createStory("Build kayak rental checkout");

        // ...means a newly created task whose words clearly overlap it (kayak,
        // checkout) is auto-filed onto that story as a step, not orphaned. It queues
        // (PENDING) like every task and runs in its story's chain order.
        mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Refine the kayak checkout flow\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Build kayak rental checkout")))
                .andExpect(jsonPath("$.storyOrder", is(0)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.storyPlanner", is(false)));

        // The classified task now lives on the story (planner + this step).
        mvc.perform(get("/api/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Build kayak rental checkout")))
                .andExpect(jsonPath("$[0].steps.length()", is(2)));
    }

    @Test
    void newTodoWithNoStoryOverlapStaysStandalone() throws Exception {
        // Stories exist, but a task about something entirely different is left
        // unclassified rather than force-fitted into an unrelated story.
        createStory("Tune the caching layer");

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
        createStory("Harden authentication flow");

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

    @Test
    void storyStepIsQueuedNotStaged() throws Exception {
        // There's no separate staging step anymore: a worker-generated story step is
        // queued (PENDING) as soon as it's created and runs automatically in chain
        // order — no manual promotion needed.
        String storyId = createStory("Ship export feature");
        mvc.perform(post("/api/stories/" + storyId + "/steps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Add export endpoint\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.storyOrder", is(0)));
    }

    @Test
    void storyPlannerSeedIsQueuedNotStaged() throws Exception {
        // The planner seed itself is PENDING (queued for the worker to plan) — it is
        // the one story task that is NOT staged, so planning starts on the next poll.
        String storyId = createStory("Add a settings page");
        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                // Newest first: the planner we just created is at index 0.
                .andExpect(jsonPath("$[0].storyId", is(storyId)))
                .andExpect(jsonPath("$[0].storyPlanner", is(true)))
                .andExpect(jsonPath("$[0].status", is("PENDING")));
    }

    @Test
    void patchRejectsRemovedNewStatus() throws Exception {
        // The NEW ("staged") status was removed — every task queues straight to To
        // Do — so PATCHing a task to "new" is now an unknown status (400).
        long id = createTodo("No staging");

        mvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"new\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyNewMigrationRunsCleanlyAndKeepsBoardReadable() throws Exception {
        // A task left over from before the NEW status was removed would be persisted
        // as status='NEW'. The startup migration rewrites any such row to PENDING
        // with raw SQL so the removed enum value never reaches JPA. On a schema that
        // no longer permits NEW at all (like this fresh test DB) there is nothing to
        // migrate and the pass is a clean, swallowed no-op — it must never throw or
        // disturb valid rows.
        long id = createTodo("Legacy staged task");

        migration.migrate();

        mvc.perform(get("/api/todos/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void manuallyFilesTaskUnderStory() throws Exception {
        String storyId = createStory("Redesign avatar gallery");
        long id = createTodo("A standalone chore");

        mvc.perform(patch("/api/todos/" + id + "/story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":\"" + storyId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storyId", is(storyId)))
                .andExpect(jsonPath("$.storyName", is("Redesign avatar gallery")))
                // status is untouched — filing is organisation, not a re-run.
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void unassigningTaskMakesItStandalone() throws Exception {
        String storyId = createStory("Ship search feature");
        long id = createTodo("A chore");
        mvc.perform(patch("/api/todos/" + id + "/story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":\"" + storyId + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/todos/" + id + "/story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storyId", is(nullValue())))
                .andExpect(jsonPath("$.storyName", is(nullValue())));
    }

    @Test
    void filingUnderUnknownStoryReturns404() throws Exception {
        long id = createTodo("A chore");
        mvc.perform(patch("/api/todos/" + id + "/story")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":\"does-not-exist\"}"))
                .andExpect(status().isNotFound());
    }
}
