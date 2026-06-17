package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import org.hamcrest.Matchers;
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

    @Autowired
    ShareLinkService shareLinkService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void filesPageRendersToastRegion() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"toastRegion\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Open read-only mode")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Recent")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Favorites")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Trash"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Shared links"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Remote download"))));
    }

    @Test
    void directoriesStayTableInGridViewAndExposeEnhancedSelectAll() throws Exception {
        String directory = "grid-directory-" + System.nanoTime();
        String filename = "grid-file-" + System.nanoTime() + ".txt";
        Files.createDirectories(ROOT.resolve(directory));
        Files.writeString(ROOT.resolve(filename), "grid");

        mockMvc.perform(get("/files").param("view", "grid"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(directory)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-select-pick-label")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-select-all")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("directory-card"))));
    }

    @Test
    void gridFileCardsUseCompactPreviewOnlyActions() throws Exception {
        String filename = "grid-card-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "grid");

        mockMvc.perform(get("/files").param("view", "grid"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("thumb-extension")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("card-name")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("card-actions"))));
    }

    @Test
    void readOnlyPageLoadsEnhancedTapTargetScript() throws Exception {
        String directory = "readonly-dir-" + System.nanoTime();
        String filename = "readonly-file-" + System.nanoTime() + ".txt";
        Files.createDirectories(ROOT.resolve(directory));
        Files.writeString(ROOT.resolve(filename), "readonly");

        mockMvc.perform(get("/files/read-only"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/read-only.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly-table")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly-file-card")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(directory)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)));
    }

    @Test
    void dashboardPageRendersSummaryPanels() throws Exception {
        mockMvc.perform(get("/files/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Task Manager")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("System Health")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Management")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Utils")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Shared links")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Activity logs")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Trash")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Telegram alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote download")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Page archiving")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Storage remaining")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Enable Telegram alerts"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Quick Actions"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Activity Log"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Maintenance"))));
    }

    @Test
    void telegramAlertsPageRendersSettingsForm() throws Exception {
        mockMvc.perform(get("/files/telegram-alerts"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Telegram Alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enable Telegram alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bot token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chat ID")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LOGIN_SUCCESS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save and Apply")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Send Test Message")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-password-toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Show bot token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/admin-actions.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"telegram-settings-save\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"telegram-settings-test\"")));
    }

    @Test
    void recentPageRendersVirtualFolderShell() throws Exception {
        mockMvc.perform(get("/files/recent")
                        .param("q", "unlikely-recent-query-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Virtual location")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Search in recent")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No recent items matched your search.")));
    }

    @Test
    void recentPageRecordsDetailAccessAndSearchesRecentItems() throws Exception {
        String filename = "recent-ui-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "recent");

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk());

        mockMvc.perform(get("/files/recent").param("q", "recent-ui"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accessed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove selected from recent")));
    }

    @Test
    void favoritesCanBeToggledAndManaged() throws Exception {
        String filename = "favorite-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");

        mockMvc.perform(post("/files/favorites/toggle")
                        .with(csrf())
                        .header("Referer", "http://localhost/files")
                        .param("path", filename))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andExpect(flash().attributeExists(FlashNotifications.ATTRIBUTE_NAME));

        mockMvc.perform(get("/files/favorites"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Move up")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove")));

        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("is-favorite")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-favorite-action")));
    }

    @Test
    void favoriteToggleCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "favorite-json-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");

        mockMvc.perform(post("/files/favorites/toggle")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.favorite.path").value(filename))
                .andExpect(jsonPath("$.notification.type").value("success"));
    }

    @Test
    void remoteDownloadPageRendersForm() throws Exception {
        mockMvc.perform(get("/files/remote-download"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote Download")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"url\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"path\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote download")));
    }

    @Test
    void logsPageRendersActivityEntries() throws Exception {
        String directory = "log-dir-" + System.nanoTime();

        mockMvc.perform(post("/files/directories")
                        .with(csrf())
                        .param("name", directory))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/files/logs")
                        .param("type", "CREATE_DIRECTORY")
                        .param("q", directory)
                        .param("order", "oldest"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CREATE_DIRECTORY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Apply filters")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("127.0.0.1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(directory)));
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
    void sharedDownloadSupportsFilenamePathForCommandLineClients() throws Exception {
        String filename = "wget sample, " + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "shared download", StandardCharsets.UTF_8);
        ShareLink shareLink = shareLinkService.create("", filename, null);
        String encodedFilename = filename.replace(" ", "%20");
        String contentDispositionFilename = encodedFilename.replace(",", "%2C");

        mockMvc.perform(get("/s/{token}", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(
                        "/s/" + shareLink.token() + "/download/" + encodedFilename)));

        mockMvc.perform(get(URI.create("/s/" + shareLink.token() + "/download/" + encodedFilename)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        Matchers.containsString("filename*=UTF-8''" + contentDispositionFilename)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("shared download".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sharedFileLandingRendersImagePreviewInline() throws Exception {
        String filename = "shared-image-" + System.nanoTime() + ".jpg";
        Files.write(ROOT.resolve(filename), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        ShareLink shareLink = shareLinkService.create("", filename, null);

        mockMvc.perform(get("/s/{token}", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("shared-preview-panel")))
                .andExpect(content().string(Matchers.containsString("<img class=\"preview-media\"")))
                .andExpect(content().string(Matchers.containsString("/s/" + shareLink.token() + "/preview")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("fa-eye"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("target=\"_blank\""))));
    }

    @Test
    void sharedFileLandingRendersTextPreviewEscaped() throws Exception {
        String filename = "shared-text-" + System.nanoTime() + ".html";
        Files.writeString(ROOT.resolve(filename), "<script>alert(1)</script>", StandardCharsets.UTF_8);
        ShareLink shareLink = shareLinkService.create("", filename, null);

        MvcResult result = mockMvc.perform(get("/s/{token}", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/webjars/codemirror/5.65.19/lib/codemirror.js")))
                .andExpect(content().string(Matchers.containsString("/webjars/codemirror/5.65.19/addon/mode/simple.js")))
                .andExpect(content().string(Matchers.containsString("/js/file-tools.js")))
                .andExpect(content().string(Matchers.containsString("data-shared-text-preview")))
                .andExpect(content().string(Matchers.containsString("data-text-extension=\"html\"")))
                .andExpect(content().string(Matchers.containsString("Text Preview")))
                .andExpect(content().string(Matchers.containsString("shared-download-button")))
                .andExpect(content().string(Matchers.containsString("<span>Download</span>")))
                .andExpect(content().string(Matchers.containsString("shared-file-details")))
                .andExpect(content().string(Matchers.containsString("data-shared-text-source")))
                .andExpect(content().string(Matchers.containsString("readonly")))
                .andExpect(content().string(Matchers.containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<script>alert"))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body.indexOf("/webjars/codemirror/5.65.19/addon/mode/simple.js"))
                .isLessThan(body.indexOf("/webjars/codemirror/5.65.19/mode/rust/rust.js"));
    }

    @Test
    void sharedDirectoryPreviewActionUsesFileLandingPage() throws Exception {
        String directory = "shared-directory-" + System.nanoTime();
        String subdirectory = "series";
        String filename = "chapter.txt";
        Files.createDirectories(ROOT.resolve(directory).resolve(subdirectory));
        Files.writeString(ROOT.resolve(directory).resolve(subdirectory).resolve(filename), "chapter text", StandardCharsets.UTF_8);
        ShareLink shareLink = shareLinkService.create("", directory, null);

        mockMvc.perform(get("/s/{token}", shareLink.token()).param("path", subdirectory))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/js/file-selection.js")))
                .andExpect(content().string(Matchers.containsString("data-select-pick-label")))
                .andExpect(content().string(Matchers.containsString("data-select-all")))
                .andExpect(content().string(Matchers.containsString("class=\"select-cell\"")))
                .andExpect(content().string(Matchers.containsString("Download selected file")))
                .andExpect(content().string(Matchers.containsString(
                        "/s/" + shareLink.token() + "/file?item=chapter.txt&amp;path=series")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("target=\"_blank\""))));

        mockMvc.perform(get("/s/{token}/file", shareLink.token())
                        .param("path", subdirectory)
                        .param("item", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Text Preview")))
                .andExpect(content().string(Matchers.containsString("chapter text")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "/s/" + shareLink.token() + "/preview?path=series&amp;item=chapter.txt"))));
    }

    @Test
    void sharedDirectoryDownloadZipRedirectsWhenNothingSelected() throws Exception {
        String directory = "shared-empty-download-" + System.nanoTime();
        String subdirectory = "series";
        Files.createDirectories(ROOT.resolve(directory).resolve(subdirectory));
        ShareLink shareLink = shareLinkService.create("", directory, null);

        mockMvc.perform(get("/s/{token}/download.zip", shareLink.token())
                        .param("path", subdirectory))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/s/" + shareLink.token() + "?path=series"));
    }

    @Test
    void textDetailPageRendersEditorAndSavesContent() throws Exception {
        String filename = "text-editor-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "before", StandardCharsets.UTF_8);

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("File Tools")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Text Editor")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"content\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("before")));

        mockMvc.perform(post("/files/detail/text")
                        .with(csrf())
                        .param("path", filename)
                        .param("content", "after"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/detail?path=" + filename))
                .andExpect(flash().attributeExists(FlashNotifications.ATTRIBUTE_NAME));

        assertThat(Files.readString(ROOT.resolve(filename), StandardCharsets.UTF_8)).isEqualTo("after");
    }

    @Test
    void largeTextDetailPageDefersEditorUntilManualLoad() throws Exception {
        String filename = "large-text-editor-" + System.nanoTime() + ".txt";
        String largeContent = "large-text-" + "x".repeat(1024 * 1024);
        Files.writeString(ROOT.resolve(filename), largeContent, StandardCharsets.UTF_8);

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Load text")))
                .andExpect(content().string(Matchers.containsString("Download original")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"content\""))));

        mockMvc.perform(get("/files/detail/text/load")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.text.editable").value(true))
                .andExpect(jsonPath("$.text.content", Matchers.startsWith("large-text-")));
    }

    @Test
    void textSaveCanReturnJsonForEnhancedEditor() throws Exception {
        String filename = "text-editor-json-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "before", StandardCharsets.UTF_8);

        mockMvc.perform(post("/files/detail/text")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename)
                        .param("content", "after json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("success"));

        assertThat(Files.readString(ROOT.resolve(filename), StandardCharsets.UTF_8)).isEqualTo("after json");
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
    void selectedSingleFileDownloadReturnsAttachment() throws Exception {
        String filename = "selected-download-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "selected download", StandardCharsets.UTF_8);

        mockMvc.perform(get("/files/download.zip")
                        .param("items", filename))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString(filename)))
                .andExpect(content().bytes("selected download".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void selectedMultipleFileDownloadStreamsZip() throws Exception {
        String first = "selected-zip-a-" + System.nanoTime() + ".txt";
        String second = "selected-zip-b-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(first), "first", StandardCharsets.UTF_8);
        Files.writeString(ROOT.resolve(second), "second", StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(get("/files/download.zip")
                        .param("items", first)
                        .param("items", second))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("endervault.zip")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, Matchers.containsString("application/zip")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
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
