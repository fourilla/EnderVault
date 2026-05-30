package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class AdminNotificationFlowTest {

    private static final Path ROOT = createTempRoot();

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void filesPageRendersToastRegion() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"toastRegion\"")));
    }

    @Test
    void expectedPostFailuresRedirectBackWithErrorToast() throws Exception {
        Files.createDirectories(ROOT.resolve("existing"));

        MvcResult result = mockMvc.perform(post("/files/directories")
                        .with(csrf())
                        .header("Referer", "http://localhost/files")
                        .param("name", "existing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists(FlashNotifications.ATTRIBUTE_NAME))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FlashNotification> notifications =
                (List<FlashNotification>) result.getFlashMap().get(FlashNotifications.ATTRIBUTE_NAME);
        assertThat(notifications)
                .extracting(FlashNotification::type)
                .containsExactly("error");
    }

    @Test
    void shareCreationCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "ajax-share-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "share");

        mockMvc.perform(post("/files/detail/share")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename)
                        .param("customToken", "ajax-share-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("info"))
                .andExpect(jsonPath("$.notification.actionValue").exists())
                .andExpect(jsonPath("$.shareLink.token").exists());
    }

    @Test
    void uploadCanReturnJsonForXhrProgressFlow() throws Exception {
        String filename = "ajax-upload-" + System.nanoTime() + ".txt";
        MockMultipartFile file = new MockMultipartFile("files", filename, "text/plain", "upload".getBytes());

        mockMvc.perform(multipart("/files/upload")
                        .file(file)
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("success"))
                .andExpect(jsonPath("$.uploadedFiles[0].name").value(filename));

        assertThat(ROOT.resolve(filename)).exists();
    }

    @Test
    void deleteSelectedCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "ajax-delete-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "delete");

        mockMvc.perform(post("/files/delete")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("items", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("success"))
                .andExpect(jsonPath("$.redirectUrl").exists());

        assertThat(ROOT.resolve(filename)).doesNotExist();
        assertThat(Files.readString(ROOT.resolve(".endervault").resolve("trash-records.json")))
                .contains(filename);
    }

    @Test
    void detailRenameCanReturnJsonRedirectForEnhancedForms() throws Exception {
        String filename = "ajax-rename-" + System.nanoTime() + ".txt";
        String renamed = "ajax-renamed-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "rename");

        mockMvc.perform(post("/files/detail/rename")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename)
                        .param("newName", renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("success"))
                .andExpect(jsonPath("$.redirectUrl").value("/files/detail?path=" + renamed));

        assertThat(ROOT.resolve(filename)).doesNotExist();
        assertThat(ROOT.resolve(renamed)).exists();
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-notifications-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }
}
