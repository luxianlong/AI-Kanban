package com.kanban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attaching an image to a task: it is uploaded before the task exists, claimed by
 * the task on create, served back for the UI preview, and — the point of the
 * feature — kept as a real file the server (and so the agent) can open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:attachmentdb;DB_CLOSE_DELAY=-1"
})
class AttachmentApiTest {

    /** The store is redirected here so the tests never touch the live data dir. */
    @TempDir
    static Path store;

    @DynamicPropertySource
    static void attachmentStore(DynamicPropertyRegistry registry) {
        registry.add("attachments.dir", () -> store.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] PNG_BYTES = "PNG\r\n\n-pretend-pixels".getBytes();

    /**
     * Whether the store holds the file for this attachment. The temp dir is
     * shared by every test in the class, so assertions must be per-attachment
     * rather than "the directory is empty".
     */
    private boolean storedOnDisk(String attachmentId) throws Exception {
        try (var files = Files.list(store)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(attachmentId));
        }
    }

    /** Uploads an image and returns its generated id. */
    private String upload(String name) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "image/png", PNG_BYTES);
        String body = mvc.perform(multipart("/api/attachments").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName", is(name)))
                .andExpect(jsonPath("$.contentType", is("image/png")))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private long createTodoWith(String title, String... attachmentIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (String id : attachmentIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append('"').append(id).append('"');
        }
        String body = mvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"attachmentIds\":[" + ids + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test
    void uploadedImageIsStoredOnDiskAndClaimedByTheTask() throws Exception {
        String attachmentId = upload("screenshot.png");

        // The bytes really landed in the store — that's what the agent opens.
        assertThat(storedOnDisk(attachmentId)).isTrue();

        long todoId = createTodoWith("Rebuild this screen", attachmentId);

        mvc.perform(get("/api/todos/" + todoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()", is(1)))
                .andExpect(jsonPath("$.attachments[0].id", is(attachmentId)))
                .andExpect(jsonPath("$.attachments[0].fileName", is("screenshot.png")))
                .andExpect(jsonPath("$.attachments[0].url", is("/api/attachments/" + attachmentId)));

        // And the board listing carries them too, so a card can show the marker.
        String list = mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = null;
        for (JsonNode node : mapper.readTree(list)) {
            if (node.get("id").asLong() == todoId) {
                created = node;
            }
        }
        assertThat(created).isNotNull();
        assertThat(created.get("attachments")).hasSize(1);
    }

    @Test
    void storedImageIsServedBackForThePreview() throws Exception {
        String attachmentId = upload("photo.png");

        byte[] served = mvc.perform(get("/api/attachments/" + attachmentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(served).isEqualTo(PNG_BYTES);
    }

    @Test
    void aTaskWithoutImagesReportsAnEmptyList() throws Exception {
        long todoId = createTodoWith("Write a haiku");

        mvc.perform(get("/api/todos/" + todoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()", is(0)));
    }

    @Test
    void nonImageUploadsAreRejected() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/attachments").file(file))
                .andExpect(status().isBadRequest());

        try (var files = Files.list(store)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().endsWith(".txt"));
        }
    }

    @Test
    void removingAnUploadDeletesItsFile() throws Exception {
        String attachmentId = upload("unwanted.png");

        mvc.perform(delete("/api/attachments/" + attachmentId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/attachments/" + attachmentId))
                .andExpect(status().isNotFound());
        assertThat(storedOnDisk(attachmentId)).isFalse();
    }

    @Test
    void deletingATaskAlsoDeletesItsImages() throws Exception {
        String attachmentId = upload("bound.png");
        long todoId = createTodoWith("Task that owns an image", attachmentId);

        mvc.perform(delete("/api/todos/" + todoId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/attachments/" + attachmentId))
                .andExpect(status().isNotFound());
        assertThat(storedOnDisk(attachmentId)).isFalse();
    }

    @Test
    void rerunningATaskGivesTheCopyItsOwnImages() throws Exception {
        String attachmentId = upload("original.png");
        long todoId = createTodoWith("Task with an image", attachmentId);

        String body = mvc.perform(post("/api/todos/" + todoId + "/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()", is(1)))
                .andExpect(jsonPath("$.attachments[0].fileName", is("original.png")))
                .andReturn().getResponse().getContentAsString();
        JsonNode copied = mapper.readTree(body).get("attachments").get(0);
        String copyId = copied.get("id").asText();

        // The copy owns a distinct file with the same bytes, so deleting either
        // task leaves the other's image intact.
        assertThat(copyId).isNotEqualTo(attachmentId);
        assertThat(storedOnDisk(copyId)).isTrue();
        assertThat(mvc.perform(get("/api/attachments/" + copyId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        mvc.perform(delete("/api/todos/" + todoId))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attachments/" + copyId)).andExpect(status().isOk());
    }

    @Test
    void anUnknownAttachmentIdIsIgnoredRatherThanFailingTheTask() throws Exception {
        long todoId = createTodoWith("Task with a stale id", "does-not-exist");

        mvc.perform(get("/api/todos/" + todoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()", is(0)));
    }
}
