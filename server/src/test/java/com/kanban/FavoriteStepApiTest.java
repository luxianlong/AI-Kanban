package com.kanban;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.workflow.FavoriteStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shelf of favourited steps: saving one, listing them, and taking one off again.
 *
 * <p>Inserting a favourite into a graph is deliberately absent here — that happens
 * entirely in the editor, which writes an ordinary node from the favourite's text and
 * then saves the workflow the way it always does. There is no "insert" endpoint for a
 * server test to call; what the editor writes is tested in {@code workflowDraft.test.mjs}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:favoritesteptestdb;DB_CLOSE_DELAY=-1"
})
class FavoriteStepApiTest {

    @Autowired
    MockMvc mvc;

    /** Emptied per test: the shelf is one global list, so tests would see each other's. */
    @Autowired
    FavoriteStepRepository favorites;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearShelf() {
        favorites.deleteAll();
    }

    private static String favorite(String name, String prompt, String effort) {
        return "{\"name\":\"" + name + "\",\"prompt\":\"" + prompt + "\""
                + (effort == null ? "" : ",\"effort\":\"" + effort + "\"") + "}";
    }

    private String save(String body) throws Exception {
        String json = mvc.perform(post("/api/workflows/favorites")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    @Test
    void aStepIsSavedWithItsPromptAndEffortAndComesBackInTheList() throws Exception {
        save(favorite("Summarise", "Summarise the input in five bullets", "low"));

        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Summarise")))
                .andExpect(jsonPath("$[0].prompt", is("Summarise the input in five bullets")))
                .andExpect(jsonPath("$[0].effort", is("low")));
    }

    /** A step saved with no level inserts at the CLI's default, so it stores null, not "". */
    @Test
    void aStepSavedWithoutALevelKeepsNullRatherThanABlankOne() throws Exception {
        save(favorite("Tidy", "Fix the typos", ""));

        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$[0].effort", is(nullValue())));
    }

    /** The shelf is something to find a name on, so it is alphabetical, not newest-first. */
    @Test
    void theShelfIsListedByName() throws Exception {
        save(favorite("Publish", "Write it up", null));
        save(favorite("Collect", "Find sources", null));
        save(favorite("Summarise", "Summarise it", null));

        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name", is("Collect")))
                .andExpect(jsonPath("$[1].name", is("Publish")))
                .andExpect(jsonPath("$[2].name", is("Summarise")));
    }

    /**
     * Saving over a name replaces that favourite rather than growing a second one:
     * two entries with the same label are indistinguishable in the picker, and the
     * second one is nearly always the first one edited.
     */
    @Test
    void savingUnderANameThatIsTakenReplacesThatFavourite() throws Exception {
        String first = save(favorite("Summarise", "Summarise the input", "low"));
        String second = save(favorite("Summarise", "Summarise it in five bullets", "high"));

        assertThat(second).isEqualTo(first);
        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].prompt", is("Summarise it in five bullets")))
                .andExpect(jsonPath("$[0].effort", is("high")));
    }

    /** Same rule with different casing — it is the same shelf label to a reader. */
    @Test
    void aNameThatDiffersOnlyInCasingIsTheSameFavouriteAndTakesTheNewSpelling() throws Exception {
        save(favorite("summarise", "first", null));
        save(favorite("Summarise", "second", null));

        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Summarise")))
                .andExpect(jsonPath("$[0].prompt", is("second")));
    }

    /**
     * The same refusal a step in the editor gets: a level that is not a level is a 400
     * naming the ones that are, never a quiet fallback to the default (see EffortLevel).
     * Otherwise every step inserted from this favourite would claim a level it never ran at.
     */
    @Test
    void aFavouriteWithAnEffortLevelThatIsNotOneIsRejected() throws Exception {
        mvc.perform(post("/api/workflows/favorites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favorite("Odd", "x", "turbo")))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("low, medium, high, xhigh, max, ultracode")));

        mvc.perform(get("/api/workflows/favorites")).andExpect(jsonPath("$", hasSize(0)));
    }

    /** Ultracode is a level here as much as it is on a step, so it saves and inserts. */
    @Test
    void aFavouriteMayCarryTheUltracodeLevel() throws Exception {
        save(favorite("Deep dive", "Work it out", "ultracode"));

        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$[0].effort", is("ultracode")));
    }

    @Test
    void aFavouriteWithoutANameIsRejected() throws Exception {
        mvc.perform(post("/api/workflows/favorites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favorite("   ", "x", null)))
                .andExpect(status().isBadRequest());
    }

    /** The shelf holds a bounded number, and says so instead of quietly dropping one. */
    @Test
    void theShelfIsCappedAndSavingOverAnExistingNameStillWorksWhenItIsFull() throws Exception {
        for (int i = 0; i < 100; i++) {
            save(favorite("Step " + i, "prompt " + i, null));
        }

        mvc.perform(post("/api/workflows/favorites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favorite("One more", "x", null)))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Remove one")));

        // Replacing one already on a full shelf adds nothing, so it is still allowed.
        save(favorite("Step 7", "rewritten", null));
        mvc.perform(get("/api/workflows/favorites")).andExpect(jsonPath("$", hasSize(100)));
    }

    @Test
    void aFavouriteCanBeTakenOffTheShelf() throws Exception {
        String id = save(favorite("Summarise", "Summarise it", null));

        mvc.perform(delete("/api/workflows/favorites/" + id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/workflows/favorites"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deletingAFavouriteThatIsNotThereAnswers404() throws Exception {
        mvc.perform(delete("/api/workflows/favorites/nope"))
                .andExpect(status().isNotFound());
    }

    /**
     * A favourite is a copy, not a reference: a workflow built from one keeps its text
     * when the favourite is edited or deleted. This is the whole reason the editor can
     * insert one and then let the user change it freely.
     */
    @Test
    void aWorkflowBuiltFromAFavouriteIsUnaffectedWhenTheFavouriteChanges() throws Exception {
        save(favorite("Summarise", "Summarise the input", "low"));

        String workflow = mvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Built from a favourite",
                                 "nodes":[{"id":"a","name":"Summarise",
                                           "prompt":"Summarise the input","effort":"low"}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String workflowId = mapper.readTree(workflow).get("id").asText();

        String favouriteId = save(favorite("Summarise", "Something else entirely", "max"));
        mvc.perform(delete("/api/workflows/favorites/" + favouriteId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/workflows/" + workflowId))
                .andExpect(jsonPath("$.nodes[0].prompt", is("Summarise the input")))
                .andExpect(jsonPath("$.nodes[0].effort", is("low")));
    }
}
