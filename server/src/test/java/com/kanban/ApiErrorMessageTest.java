package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A refusal has to arrive carrying the sentence that says why.
 *
 * <p>The API declines things in prose written at the point the code knows which case it
 * is — which of the four reasons a step is not waiting to be started, that the nodes form
 * a cycle — and Boot drops all of it from the response body unless asked not to. So the
 * browser received {@code {"status":409,"error":"Conflict"}} and had to invent a reason to
 * show, which is right one time in four. These are the tests that stop that from coming
 * back, since nothing else fails when it does: the status code is correct either way, and
 * every existing test asserts on the status.
 *
 * <p>The other half matters as much and is easier to lose: a reason is published because
 * somebody wrote it for a reader, so an exception that carries no such sentence must keep
 * saying nothing rather than start volunteering whatever its {@code getMessage()} holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:apierrordb;DB_CLOSE_DELAY=-1"
})
class ApiErrorMessageTest {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private MockHttpServletResponse refusalFrom(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    /**
     * The body, or an empty node when there isn't one. MockMvc stops at the handler and
     * never runs the servlet's error dispatch, so a status Boot would have rendered a
     * default body for arrives here with no body at all — which is exactly the
     * "said nothing" case the last test is about, and must be read rather than crashed on.
     */
    private JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        String json = response.getContentAsString();
        return json.isBlank() ? MissingNode.getInstance() : mapper.readTree(json);
    }

    /**
     * Starting a step of a run that does not exist. The 404 is raised with a reason, so the
     * reason is what the caller gets to read — and it is in Boot's own field, because the
     * page's reader ({@code cleanError}) was written against that shape long before the
     * field was ever populated.
     */
    @Test
    void a_refusal_carries_the_sentence_the_api_wrote() throws Exception {
        MockHttpServletResponse response = refusalFrom("/api/workflows/node-runs/424242/start", "");
        JsonNode error = bodyOf(response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(error.path("status").asInt()).isEqualTo(404);
        assertThat(error.path("error").asText()).isEqualTo("Not Found");
        assertThat(error.path("message").asText()).isEqualTo("Node run not found: 424242");
        assertThat(error.path("path").asText()).isEqualTo("/api/workflows/node-runs/424242/start");
    }

    /**
     * The same, from a different controller in a different package. The advice is global
     * and the reasons it carries were written all over the codebase, so pinning it to the
     * one endpoint it was written for would let a later narrowing pass unnoticed.
     */
    @Test
    void the_sentence_is_not_particular_to_one_endpoint() throws Exception {
        MockHttpServletResponse response = refusalFrom("/api/todos/424242/rerun", "{}");
        JsonNode error = bodyOf(response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(error.path("message").asText()).isEqualTo("Todo not found: 424242");
    }

    /**
     * An unmapped path. Nobody wrote a sentence for this one, so the body must not grow a
     * {@code message} field — that is the line between publishing what was written for a
     * reader and publishing whatever an exception happens to say, which is the whole reason
     * this is an advice over {@code ResponseStatusException} and not
     * {@code server.error.include-message: always}.
     */
    @Test
    void an_exception_with_nothing_to_say_stays_quiet() throws Exception {
        MockHttpServletResponse response = refusalFrom("/api/no-such-endpoint-at-all", "");
        JsonNode error = bodyOf(response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(error.path("message").asText()).doesNotContain("no-such-endpoint-at-all");
    }
}
