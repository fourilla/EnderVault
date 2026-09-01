package io.github.fourilla.endervault.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class StickyNoteFlowTest {

    private static final Path ROOT = createTempRoot();
    private static final DateTimeFormatter UPDATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void adminShellExposesStickyNoteContextButReadOnlyDoesNot() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("endervault-sticky-target-type")))
                .andExpect(content().string(containsString("data-sticky-note-controls")))
                .andExpect(content().string(containsString("data-sticky-note-visibility")))
                .andExpect(content().string(containsString("data-sticky-note-add")))
                .andExpect(content().string(containsString("/admin/settings?section=appearance#sticky-note-theme")))
                .andExpect(content().string(containsString("Note appearance")))
                .andExpect(content().string(containsString("href=\"/admin/sticky-notes\"")))
                .andExpect(content().string(containsString("Note list")))
                .andExpect(content().string(not(containsString("data-sticky-note-collapse-all"))))
                .andExpect(content().string(containsString("/react/assets/shell-")))
                .andExpect(content().string(not(containsString("/js/topbar-controls.js"))))
                .andExpect(content().string(not(containsString("/js/sticky-notes.js"))))
                .andExpect(content().string(not(containsString("/js/page-context-menu.js"))));

        mockMvc.perform(get("/files/read-only"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-sticky-note-controls"))))
                .andExpect(content().string(not(containsString("endervault-sticky-target-type"))));
    }

    @Test
    void createsUpdatesListsAndDeletesStickyNote() throws Exception {
        String createJson = objectMapper.writeValueAsString(Map.of(
                "targetType", "STORAGE",
                "targetKey", "",
                "surface", "BROWSER",
                "x", 12,
                "y", 24
        ));
        MvcResult created = mockMvc.perform(post("/api/v1/sticky-notes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.note.id").isNotEmpty())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("note")
                .path("id")
                .asText();

        String updateJson = objectMapper.writeValueAsString(Map.of(
                "content", "MockMvc sticky note",
                "x", 30,
                "y", 40,
                "width", 320,
                "height", 240,
                "collapsed", false,
                "layer", 2
        ));
        MvcResult updated = mockMvc.perform(put("/api/v1/sticky-notes/{id}", id)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note.content").value("MockMvc sticky note"))
                .andReturn();
        String updatedLabel = UPDATED_AT_FORMATTER.format(Instant.parse(
                objectMapper.readTree(updated.getResponse().getContentAsByteArray())
                        .path("note")
                        .path("updatedAt")
                        .asText()
        ));

        mockMvc.perform(get("/api/v1/sticky-notes")
                        .param("targetType", "STORAGE")
                        .param("targetKey", "")
                        .param("surface", "BROWSER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].id").value(id));

        mockMvc.perform(get("/admin/sticky-notes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"admin-app-root\"")))
                .andExpect(content().string(not(containsString("data-sticky-note-manager-delete"))));

        mockMvc.perform(get("/api/v1/sticky-notes/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[*].id", hasItem(id)))
                .andExpect(jsonPath("$.notes[*].content", hasItem("MockMvc sticky note")))
                .andExpect(jsonPath("$.notes[*].updatedLabel", hasItem(updatedLabel)))
                .andExpect(jsonPath("$.notes[0].context.targetKey").doesNotExist());

        mockMvc.perform(delete("/api/v1/sticky-notes/{id}", id)
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedId").value(id));
    }

    @Test
    void removedStickyNoteItemEndpointsAreNotAvailable() throws Exception {
        mockMvc.perform(get("/admin/sticky-notes/items")
                        .param("targetType", "PAGE")
                        .param("surface", "PAGE"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/sticky-notes/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/sticky-notes/items/legacy/delete").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedSearchHtmlEndpointIsNotAvailable() throws Exception {
        mockMvc.perform(get("/files/search"))
                .andExpect(status().isNotFound());
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-sticky-note-flow-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create sticky note test root.", ex);
        }
    }
}
