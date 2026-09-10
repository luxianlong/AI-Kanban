package com.kanban;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delegated ("代理") tasks (task #471): a task this server never resolves, published
 * on the unauthenticated {@code /api/public/tasks} API so an outside worker can find
 * it, take it, and report the outcome back onto the board.
 *
 * <p>These tests guard both halves of the contract: the flow works end to end, and
 * the public surface exposes nothing but delegated tasks — that scoping is the only
 * thing standing between a stranger and the private board.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:delegatedb;DB_CLOSE_DELAY=-1"
})
class DelegatedTaskApiTest {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private long create(String title, boolean delegated) throws Exception {
        String body = mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"context\","
                                + "\"delegated\":" + delegated + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delegated", is(delegated)))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    /** Claims a delegated task and returns the token minted for the claimer. */
    private String claim(long id, String worker) throws Exception {
        String body = mvc.perform(post("/api/public/tasks/" + id + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"" + worker + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.task.claimedBy", is(worker)))
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    @Test
    void anOutsideWorkerCanFindClaimAndReportADelegatedTask() throws Exception {
        long id = create("Book the venue", true);

        // 1. It shows up on the public feed of open work, with its details.
        mvc.perform(get("/api/public/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].title", is(java.util.List.of("Book the venue"))))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].description", is(java.util.List.of("context"))));

        // 2. Claiming it hands out the token and shows who took it.
        String token = claim(id, "outside-bot");

        // 3. Reporting the outcome lands the answer on the board itself.
        mvc.perform(post("/api/public/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"result\":\"booked for the 3rd\","
                                + "\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESOLVED")));

        mvc.perform(get("/api/todos/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESOLVED")))
                .andExpect(jsonPath("$.result", is("booked for the 3rd")))
                .andExpect(jsonPath("$.delegatedTo", is("outside-bot")))
                // Finishing it lights the board's unread badge, exactly like a task
                // this server resolved itself.
                .andExpect(jsonPath("$.unread", is(true)));
    }

    @Test
    void anUnclaimedTaskCanBeReportedDirectly() throws Exception {
        long id = create("Quick answer wanted", true);

        mvc.perform(post("/api/public/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"result\":\"42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", is("42")));
    }

    @Test
    void aClaimedTaskCannotBeReportedWithoutTheToken() throws Exception {
        long id = create("Mine, not yours", true);
        claim(id, "first-bot");

        mvc.perform(post("/api/public/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"result\":\"hijacked\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/public/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"result\":\"hijacked\",\"token\":\"guessed\"}"))
                .andExpect(status().isForbidden());

        // The claimer's task is untouched.
        mvc.perform(get("/api/public/tasks/" + id))
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.result", emptyOrNullString()));
    }

    @Test
    void aTaskCanOnlyBeClaimedOnce() throws Exception {
        long id = create("First come first served", true);
        claim(id, "first-bot");

        mvc.perform(post("/api/public/tasks/" + id + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker\":\"second-bot\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void handingATaskBackReleasesTheClaimForTheNextWorker() throws Exception {
        long id = create("Too hard for me", true);
        String token = claim(id, "first-bot");

        mvc.perform(post("/api/public/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\",\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.claimedBy", emptyOrNullString()));

        // Released: somebody else can take it now, with a fresh token.
        claim(id, "second-bot");
    }

    @Test
    void thePublicApiOnlyEverExposesDelegatedTasks() throws Exception {
        long normal = create("Private board work", false);
        long delegated = create("Handed out", true);

        // A normal task is invisible here — 404, the same answer a made-up id gets,
        // so the endpoint can't be used to probe which ids the board holds.
        mvc.perform(get("/api/public/tasks/" + normal)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/tasks/999999")).andExpect(status().isNotFound());
        // ...and it can't be claimed or reported on either.
        mvc.perform(post("/api/public/tasks/" + normal + "/claim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/public/tasks/" + normal + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"result\":\"nope\"}"))
                .andExpect(status().isNotFound());

        // The listing never mentions it.
        mvc.perform(get("/api/public/tasks?status=all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + normal + ")]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == " + delegated + ")]").isNotEmpty());
    }

    @Test
    void theFeedFiltersByLifecycleState() throws Exception {
        long open = create("Still open", true);
        long done = create("Already answered", true);
        mvc.perform(post("/api/public/tasks/" + done + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"result\":\"done\"}"))
                .andExpect(status().isOk());

        // The default feed is open work only — a finished task is not offered again.
        mvc.perform(get("/api/public/tasks"))
                .andExpect(jsonPath("$[?(@.id == " + open + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == " + done + ")]").isEmpty());

        mvc.perform(get("/api/public/tasks?status=RESOLVED"))
                .andExpect(jsonPath("$[?(@.id == " + done + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == " + open + ")]").isEmpty());

        mvc.perform(get("/api/public/tasks?status=nonsense"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOrdinaryTaskIsNotDelegatedByDefault() throws Exception {
        long id = create("Normal work", false);

        mvc.perform(get("/api/todos/" + id))
                .andExpect(jsonPath("$.delegated", is(false)))
                .andExpect(jsonPath("$.delegatedTo", emptyOrNullString()));
    }
}
