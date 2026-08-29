package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadRepository;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadSource;
import io.github.fourilla.endervault.upload.ResumableUploadStatus;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ViteAssetService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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

    @Autowired
    FavoriteService favoriteService;

    @Autowired
    BookmarkService bookmarkService;

    @Autowired
    FileRequestService fileRequestService;

    @Autowired
    ResumableUploadService resumableUploadService;

    @Autowired
    ResumableUploadRepository resumableUploadRepository;

    @Autowired
    StorageService storageService;

    @Autowired
    TrashService trashService;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ViteAssetService viteAssetService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
        registry.add("nas.sticky-notes.background-color", () -> "#1B3033");
        registry.add("nas.sticky-notes.border-color", () -> "#4E8F8A");
        registry.add("nas.sticky-notes.text-color", () -> "#EAF6F4");
    }

    @Test
    void filesPageRendersToastRegion() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf_header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"files-root\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/react/assets/files-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/file-uploads.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"toastRegion\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-notification-center")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/react/assets/styles-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/react/assets/shell-")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/js/notification-center.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-file-requests-enabled=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-outbound-route-form")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "action=\"/api/v1/outbound-route\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-read-only-link")))
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
    void pagesWithCommonAjaxActionsLoadTheirFormBinder() throws Exception {
        for (String path : List.of("/files/read-only", "/admin/trash", "/admin/logs")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("/js/admin-actions.js")));
        }

        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/react/assets/files-")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/js/admin-actions.js"))));

        mockMvc.perform(get("/files/recent"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/react/assets/recent-")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/js/admin-actions.js"))));
    }

    @Test
    void outboundRouteUsesVersionedApiAndLegacyEndpointIsRemoved() throws Exception {
        mockMvc.perform(post("/api/v1/outbound-route")
                        .with(csrf())
                        .param("route", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        mockMvc.perform(post("/admin/outbound/route")
                        .with(csrf())
                        .param("route", "direct"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingDecisionPageAndNotificationApiRender() throws Exception {
        mockMvc.perform(get("/admin/pending-decisions"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Files Awaiting Review")))
                .andExpect(content().string(Matchers.containsString("/js/pending-decisions.js")));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.actionableCount").isNumber())
                .andExpect(jsonPath("$.reviewAllHref").value("/admin/pending-decisions"));
    }

    @Test
    void taskPollingUsesVersionedApiAndLegacyEndpointsAreRemoved() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/tasks/cancel")
                        .with(csrf())
                        .param("id", "missing-task"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        mockMvc.perform(get("/admin/tasks"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/tasks/cancel").with(csrf()).param("id", "missing-task"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/tasks/delete").with(csrf()).param("id", "missing-task"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileRequestManagementPageRendersCreationPolicy() throws Exception {
        String destination = "request-destination-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(destination));

        mockMvc.perform(get("/admin/file-requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Create Request")))
                .andExpect(content().string(Matchers.containsString("name=\"uploaderNamePolicy\"")))
                .andExpect(content().string(Matchers.containsString("name=\"description\"")))
                .andExpect(content().string(Matchers.containsString("name=\"maxFileSizeGb\"")))
                .andExpect(content().string(Matchers.containsString("data-storage-directory-picker")))
                .andExpect(content().string(Matchers.containsString("/js/file-requests.js")))
                .andExpect(content().string(Matchers.containsString("/api/v1/file-requests")));

        mockMvc.perform(get("/admin/file-requests").param("destinationPath", destination))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"" + destination + "\"")));

        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/admin/file-requests")))
                .andExpect(content().string(Matchers.containsString("File requests")));
    }

    @Test
    void fileRequestDetailRendersImmutablePolicyAndOperationalState() throws Exception {
        String token = "detail_request_" + java.util.UUID.randomUUID().toString().replace("-", "_");
        FileRequest request = fileRequestService.create(
                "Review assets", "Upload final assets only.", "", UploaderNamePolicy.REQUIRED,
                1024, 4096, 3, List.of("png"), 7, token
        );

        mockMvc.perform(get("/admin/file-requests/{id}", request.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Request Policy")))
                .andExpect(content().string(Matchers.containsString("<dt>Title</dt>")))
                .andExpect(content().string(Matchers.containsString("<dt>Description</dt>")))
                .andExpect(content().string(Matchers.containsString("Upload final assets only.")))
                .andExpect(content().string(Matchers.containsString("Active Uploads")))
                .andExpect(content().string(Matchers.containsString("Pending Files")))
                .andExpect(content().string(Matchers.containsString(
                        "/api/v1/file-requests/" + request.id() + "/revoke"
                )))
                .andExpect(content().string(Matchers.containsString("copyFrom=" + request.id())));
    }

    @Test
    void fileRequestApiCreatesRevokesAndDeletesRequestWithCsrf() throws Exception {
        String token = "api_request_" + java.util.UUID.randomUUID().toString().replace("-", "_");

        mockMvc.perform(post("/api/v1/file-requests")
                        .with(csrf())
                        .param("title", "API request")
                        .param("description", "Created through the versioned API.")
                        .param("destinationPath", "")
                        .param("uploaderNamePolicy", "OPTIONAL")
                        .param("maxFileSizeGb", "1")
                        .param("maxTotalGb", "2")
                        .param("maxFiles", "2")
                        .param("allowedExtensions", "txt")
                        .param("expirationDays", "7")
                        .param("customToken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl", Matchers.startsWith("/admin/file-requests/")));

        FileRequest request = fileRequestService.list().stream()
                .filter(candidate -> candidate.token().equals(token))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/api/v1/file-requests/{id}/revoke", request.id()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value(Matchers.nullValue()));

        assertThat(fileRequestService.require(request.id()).enabled()).isFalse();

        mockMvc.perform(post("/api/v1/file-requests/{id}/delete", request.id()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value(Matchers.nullValue()));

        assertThat(fileRequestService.list()).noneMatch(candidate -> candidate.id().equals(request.id()));
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestAdmitsAndReceivesResumableUpload() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String filename = "public-request-" + suffix + ".txt";
        String token = "public_request_" + suffix.replace("-", "_");
        FileRequest request = fileRequestService.create(
                "Send project files",
                "Upload the requested text file.",
                "",
                UploaderNamePolicy.OPTIONAL,
                1024,
                4096,
                3,
                List.of("txt"),
                7,
                token
        );

        mockMvc.perform(get("/r/{token}", request.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Send project files")))
                .andExpect(content().string(Matchers.containsString("Upload the requested text file.")))
                .andExpect(content().string(Matchers.containsString("/js/file-request-upload.js")))
                .andExpect(content().string(Matchers.containsString("data-file-request-upload")))
                .andExpect(content().string(Matchers.containsString("file-request-upload-actions")))
                .andExpect(content().string(Matchers.containsString("accept=\".txt\"")))
                .andExpect(content().string(Matchers.containsString(
                        "/r/" + request.token() + "/upload-sessions"
                )))
                .andExpect(content().string(Matchers.not(Matchers.containsString("nas.storage.root"))));

        byte[] content = "received".getBytes(StandardCharsets.UTF_8);
        JsonNode result = admitAndUpload(
                "/r/" + request.token() + "/upload-sessions",
                filename,
                "Alice",
                content,
                "a".repeat(64)
        );

        assertThat(result.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(result.get("message").asText()).isEqualTo("Upload received.");
        assertThat(Files.readAllBytes(ROOT.resolve(filename))).isEqualTo(content);
        assertThat(fileRequestService.require(request.id()).acceptedFiles()).isEqualTo(1);
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestDoesNotRevealWhetherUploadedFilenameAlreadyExists() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String filename = "private-conflict-" + suffix + ".txt";
        String token = "private_conflict_" + suffix.replace("-", "_");
        byte[] existing = "existing".getBytes(StandardCharsets.UTF_8);
        Files.write(ROOT.resolve(filename), existing);
        FileRequest request = fileRequestService.create(
                "Private collision status",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                3,
                List.of("txt"),
                7,
                token
        );

        JsonNode result = admitAndUpload(
                "/r/" + request.token() + "/upload-sessions",
                filename,
                null,
                "replacement".getBytes(StandardCharsets.UTF_8),
                "9".repeat(64)
        );

        assertThat(result.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(result.get("message").asText()).isEqualTo("Upload received.");
        assertThat(result.get("committedPath").isNull()).isTrue();
        assertThat(result.get("pendingDecisionId").isNull()).isTrue();
        assertThat(result.get("defaultConflictPolicy").isNull()).isTrue();
        assertThat(Files.readAllBytes(ROOT.resolve(filename))).isEqualTo(existing);
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestUploadStillRequiresCsrfProof() throws Exception {
        String token = "csrf_request_" + java.util.UUID.randomUUID().toString().replace("-", "_");
        fileRequestService.create(
                "CSRF protected upload",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                3,
                List.of(),
                7,
                token
        );

        mockMvc.perform(post("/r/{token}/upload-sessions", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "filename", "csrf.txt",
                                "contentType", "text/plain",
                                "size", 4,
                                "lastModified", 0,
                                "fingerprint", "b".repeat(64)
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestDoesNotExposeMissingDestinationPath() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String destination = "private-request-destination-" + suffix;
        Files.createDirectories(ROOT.resolve(destination));
        String token = "missing_destination_" + suffix.replace("-", "_");
        fileRequestService.create(
                "Missing destination",
                destination,
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        Files.delete(ROOT.resolve(destination));

        mockMvc.perform(post("/r/{token}/upload-sessions", token)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "filename", "example.txt",
                                "contentType", "text/plain",
                                "size", 8,
                                "lastModified", 0,
                                "fingerprint", "3".repeat(64)
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.notification.message").value("File request is unavailable."))
                .andExpect(content().string(Matchers.not(Matchers.containsString(destination))));
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestAdmissionReusesActiveSessionAndCreatesNewOneAfterCancel() throws Exception {
        String token = "resume_request_" + java.util.UUID.randomUUID().toString().replace("-", "_");
        fileRequestService.create(
                "Resume upload",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        String admissionUrl = "/r/" + token + "/upload-sessions";
        String fingerprint = "d".repeat(64);

        JsonNode first = admitUploadSession(admissionUrl, "resume.txt", null, 8, fingerprint);
        JsonNode reused = admitUploadSession(
                admissionUrl, "resume.txt", null, 8, fingerprint, first.get("sessionId").asText()
        );
        assertThat(reused.get("sessionId").asText()).isEqualTo(first.get("sessionId").asText());

        JsonNode duplicate = admitUploadSession(admissionUrl, "resume.txt", null, 8, fingerprint);
        assertThat(duplicate.get("sessionId").asText()).isNotEqualTo(first.get("sessionId").asText());
        mockMvc.perform(delete(duplicate.get("statusUrl").asText()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(delete(first.get("statusUrl").asText()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(resumableUploadService.list())
                .noneMatch(session -> session.id().equals(first.get("sessionId").asText()));

        JsonNode replacement = admitUploadSession(admissionUrl, "resume.txt", null, 8, fingerprint);
        assertThat(replacement.get("sessionId").asText()).isNotEqualTo(first.get("sessionId").asText());
    }

    @Test
    @WithAnonymousUser
    void admittedUploadSessionCreatesOnlyOneProtocolResource() throws Exception {
        String token = "single_protocol_" + java.util.UUID.randomUUID().toString().replace("-", "_");
        fileRequestService.create(
                "Single protocol resource",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        JsonNode admission = admitUploadSession(
                "/r/" + token + "/upload-sessions", "single.txt", null, 8, "6".repeat(64)
        );
        String sessionId = admission.get("sessionId").asText();
        String endpoint = admission.get("endpoint").asText();
        String metadata = "filename "
                + Base64.getEncoder().encodeToString("single.txt".getBytes(StandardCharsets.UTF_8))
                + ",sessionId "
                + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(endpoint)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", 8)
                        .header("Upload-Metadata", metadata))
                .andExpect(status().isCreated());

        mockMvc.perform(post(endpoint)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", 8)
                        .header("Upload-Metadata", metadata))
                .andExpect(status().isConflict());

        mockMvc.perform(delete(admission.get("statusUrl").asText()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void fullyReceivedFileRequestUploadFinishesAfterRequestIsRevoked() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String filename = "revoked-finalize-" + suffix + ".txt";
        String token = "revoked_finalize_" + suffix.replace("-", "_");
        byte[] content = "received-before-revoke".getBytes(StandardCharsets.UTF_8);
        FileRequest request = fileRequestService.create(
                "Revoke finalization boundary",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        ResumableUploadSession session = resumableUploadService.admitFileRequest(
                token, filename, "text/plain", content.length, null, "7".repeat(64), null
        );
        Path staged = storageService.resumableUploadStagingFile(session.id());
        Files.createDirectories(staged.getParent());
        Files.write(staged, content);
        resumableUploadService.markStaged(session.id(), staged);

        fileRequestService.revoke(request.id());
        var result = resumableUploadService.finalizeStaged(session.id());

        assertThat(result.status()).isEqualTo(ResumableUploadStatus.COMPLETED);
        assertThat(Files.readAllBytes(ROOT.resolve(filename))).isEqualTo(content);
        assertThat(fileRequestService.require(request.id()).acceptedFiles()).isEqualTo(1);
    }

    @Test
    @WithAnonymousUser
    void publicUploaderCanCancelReservedSessionAfterRequestIsRevoked() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String token = "revoked_cancel_" + suffix.replace("-", "_");
        FileRequest request = fileRequestService.create(
                "Cancel after revoke",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        JsonNode admission = admitUploadSession(
                "/r/" + token + "/upload-sessions",
                "cancel-after-revoke.txt",
                null,
                8,
                "6".repeat(64)
        );

        fileRequestService.revoke(request.id());

        mockMvc.perform(delete(admission.get("statusUrl").asText()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(resumableUploadService.list())
                .noneMatch(session -> session.id().equals(admission.get("sessionId").asText()));
    }

    @Test
    void fullyReceivedFileRequestUploadFinishesAfterRequestRecordIsDeleted() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String filename = "deleted-finalize-" + suffix + ".txt";
        String token = "deleted_finalize_" + suffix.replace("-", "_");
        byte[] content = "received-before-delete".getBytes(StandardCharsets.UTF_8);
        FileRequest request = fileRequestService.create(
                "Delete finalization boundary",
                "",
                UploaderNamePolicy.NONE,
                1024,
                4096,
                2,
                List.of("txt"),
                7,
                token
        );
        ResumableUploadSession session = resumableUploadService.admitFileRequest(
                token, filename, "text/plain", content.length, null, "8".repeat(64), null
        );
        Path staged = storageService.resumableUploadStagingFile(session.id());
        Files.createDirectories(staged.getParent());
        Files.write(staged, content);
        resumableUploadService.markStaged(session.id(), staged);

        fileRequestService.delete(request.id());
        var result = resumableUploadService.finalizeStaged(session.id());

        assertThat(result.status()).isEqualTo(ResumableUploadStatus.COMPLETED);
        assertThat(Files.readAllBytes(ROOT.resolve(filename))).isEqualTo(content);
    }

    @Test
    @WithAnonymousUser
    void publicFileRequestAdmissionReservesQuotaWithoutDoubleCountingResume() throws Exception {
        String token = "quota_request_" + java.util.UUID.randomUUID().toString().replace("-", "_");
        fileRequestService.create(
                "One upload only",
                "",
                UploaderNamePolicy.NONE,
                1024,
                1024,
                1,
                List.of("txt"),
                7,
                token
        );
        String admissionUrl = "/r/" + token + "/upload-sessions";
        JsonNode admitted = admitUploadSession(admissionUrl, "first.txt", null, 8, "e".repeat(64));

        JsonNode resumed = admitUploadSession(
                admissionUrl,
                "first.txt",
                null,
                8,
                "e".repeat(64),
                admitted.get("sessionId").asText()
        );
        assertThat(resumed.get("sessionId").asText()).isEqualTo(admitted.get("sessionId").asText());

        mockMvc.perform(post(admissionUrl)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "filename", "second.txt",
                                "contentType", "text/plain",
                                "size", 8,
                                "lastModified", 0,
                                "fingerprint", "f".repeat(64)
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void expiredUploadSessionDoesNotReserveFileRequestQuota() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String token = "expired_reservation_" + suffix.replace("-", "_");
        FileRequest request = fileRequestService.create(
                "Expired reservation",
                "",
                UploaderNamePolicy.NONE,
                1024,
                1024,
                1,
                List.of("txt"),
                7,
                token
        );
        Instant now = Instant.now();
        ResumableUploadSession expired = new ResumableUploadSession(
                java.util.UUID.randomUUID().toString(),
                ResumableUploadSource.FILE_REQUEST,
                request.id(),
                "",
                "expired.txt",
                "text/plain",
                8,
                null,
                "4".repeat(64),
                now.minusSeconds(7200),
                now.minusSeconds(3600),
                ResumableUploadStatus.ADMITTED,
                null,
                null,
                null,
                null,
                null
        );
        resumableUploadRepository.save(expired);

        ResumableUploadSession admitted = resumableUploadService.admitFileRequest(
                token, "replacement.txt", "text/plain", 8, null, "5".repeat(64), null
        );

        assertThat(admitted.id()).isNotEqualTo(expired.id());
        resumableUploadService.remove(expired.id());
        resumableUploadService.remove(admitted.id());
    }

    private JsonNode admitAndUpload(
            String admissionUrl,
            String filename,
            String uploaderName,
            byte[] content,
            String fingerprint
    ) throws Exception {
        JsonNode admission = admitUploadSession(
                admissionUrl, filename, uploaderName, content.length, fingerprint
        );
        String sessionId = admission.get("sessionId").asText();
        String endpoint = admission.get("endpoint").asText();
        String metadata = "filename " + Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
                + ",sessionId " + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));

        MvcResult protocolCreation = mockMvc.perform(post(endpoint)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", content.length)
                        .header("Upload-Metadata", metadata))
                .andExpect(status().isCreated())
                .andReturn();
        String uploadUrl = protocolCreation.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(uploadUrl).isNotBlank();

        int firstChunkLength = Math.max(1, content.length / 2);
        byte[] firstChunk = java.util.Arrays.copyOfRange(content, 0, firstChunkLength);
        byte[] secondChunk = java.util.Arrays.copyOfRange(content, firstChunkLength, content.length);
        mockMvc.perform(patch(uploadUrl)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", 0)
                        .header(HttpHeaders.CONTENT_TYPE, "application/offset+octet-stream")
                        .content(firstChunk))
                .andExpect(status().isNoContent());

        mockMvc.perform(head(uploadUrl)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", String.valueOf(firstChunkLength)));

        mockMvc.perform(patch(uploadUrl)
                        .with(csrf())
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", firstChunkLength)
                        .header(HttpHeaders.CONTENT_TYPE, "application/offset+octet-stream")
                        .content(secondChunk))
                .andExpect(status().isNoContent());

        MvcResult statusResult = mockMvc.perform(get(admission.get("statusUrl").asText())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        return objectMapper.readTree(statusResult.getResponse().getContentAsByteArray());
    }

    private JsonNode admitUploadSession(
            String admissionUrl,
            String filename,
            String uploaderName,
            long size,
            String fingerprint
    ) throws Exception {
        return admitUploadSession(admissionUrl, filename, uploaderName, size, fingerprint, null);
    }

    private JsonNode admitUploadSession(
            String admissionUrl,
            String filename,
            String uploaderName,
            long size,
            String fingerprint,
            String resumeSessionId
    ) throws Exception {
        Map<String, Object> admissionRequest = new java.util.LinkedHashMap<>();
        admissionRequest.put("filename", filename);
        admissionRequest.put("contentType", "text/plain");
        admissionRequest.put("size", size);
        admissionRequest.put("lastModified", 0);
        admissionRequest.put("fingerprint", fingerprint);
        if (resumeSessionId != null) {
            admissionRequest.put("resumeSessionId", resumeSessionId);
        }
        if (uploaderName != null) {
            admissionRequest.put("uploaderName", uploaderName);
        }

        MvcResult admissionResult = mockMvc.perform(post(admissionUrl)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(admissionRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        JsonNode admission = objectMapper.readTree(admissionResult.getResponse().getContentAsByteArray());
        return admission;
    }

    @Test
    void storageEntriesEndpointReturnsRequestedDirectoryTypesOnly() throws Exception {
        Path container = ROOT.resolve("entries-api-test");
        Files.createDirectories(container.resolve("nested"));
        Files.writeString(container.resolve("note.txt"), "hello");

        mockMvc.perform(get("/api/v1/fs/entries")
                        .param("path", "entries-api-test")
                        .param("types", "directory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("entries-api-test"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].name").value("nested"))
                .andExpect(jsonPath("$.entries[0].type").value("directory"));
    }

    @Test
    void fileBrowserListingApiReturnsSharedBrowserContract() throws Exception {
        Path container = ROOT.resolve("browser-listing-api-test");
        Files.createDirectories(container.resolve("nested"));
        Files.writeString(container.resolve("note.txt"), "hello");

        mockMvc.perform(get("/api/v1/fs/listing")
                        .param("path", "browser-listing-api-test")
                        .param("view", "grid")
                        .param("sort", "name")
                        .param("dir", "asc")
                        .param("hidden", "show")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mode").value("browse"))
                .andExpect(jsonPath("$.path").value("browser-listing-api-test"))
                .andExpect(jsonPath("$.directories.length()").value(1))
                .andExpect(jsonPath("$.directories[0].name").value("nested"))
                .andExpect(jsonPath("$.directories[0].type").value("directory"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].name").value("note.txt"))
                .andExpect(jsonPath("$.entries[0].typeLabel").value("Text"))
                .andExpect(jsonPath("$.entries[0].detailUrl").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.preferences.view").value("grid"))
                .andExpect(jsonPath("$.preferences.hidden").value("show"))
                .andExpect(jsonPath("$.preferences.pageSize").value(50))
                .andExpect(jsonPath("$.search.performed").value(false));
    }

    @Test
    void fileBrowserSearchApiUsesTheSharedBrowserContract() throws Exception {
        Path container = ROOT.resolve("browser-search-api-test");
        Files.createDirectories(container.resolve("nested"));
        Files.createDirectories(container.resolve("Q3-note-directory"));
        Files.writeString(container.resolve("nested").resolve("Q11-note.txt"), "eleven");
        Files.writeString(container.resolve("nested").resolve("Q2-note.txt"), "two");

        mockMvc.perform(get("/api/v1/fs/search")
                        .param("path", "browser-search-api-test")
                        .param("q", "note")
                        .param("view", "table")
                        .param("sort", "name")
                        .param("dir", "asc")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mode").value("search"))
                .andExpect(jsonPath("$.directories.length()").value(1))
                .andExpect(jsonPath("$.directories[0].name").value("Q3-note-directory"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].name").value("Q2-note.txt"))
                .andExpect(jsonPath("$.entries[1].name").value("Q11-note.txt"))
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.preferences.view").value("table"))
                .andExpect(jsonPath("$.search.query").value("note"))
                .andExpect(jsonPath("$.search.performed").value(true));
    }

    @Test
    @WithMockUser(roles = "USER")
    void storageEntriesEndpointRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/fs/entries"))
                .andExpect(status().isForbidden());
    }

    @Test
    void storageEntriesApiErrorsStayJsonWithoutAjaxHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/fs/entries").param("types", "unknown"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.notification.message")
                        .value("Storage entry types must contain directory, file, or both."));
    }

    @Test
    void remoteDownloadPageRendersRouteSelectionAndTaskRouteColumn() throws Exception {
        mockMvc.perform(get("/admin/utils/remote-download"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"networkRoute\"")))
                .andExpect(content().string(Matchers.containsString("Use global (Direct)")))
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/remote-downloads/inspect\"")))
                .andExpect(content().string(Matchers.containsString("data-start-url=\"/api/v1/remote-downloads\"")))
                .andExpect(content().string(Matchers.containsString("data-discard-url=\"/api/v1/remote-downloads/inspect/discard\"")))
                .andExpect(content().string(Matchers.containsString("data-tasks-url=\"/api/v1/remote-downloads/tasks\"")))
                .andExpect(content().string(Matchers.containsString("id=\"remoteCurlDialog\"")))
                .andExpect(content().string(Matchers.containsString("data-storage-directory-picker")))
                .andExpect(content().string(Matchers.containsString("/js/directory-tree.js")))
                .andExpect(content().string(Matchers.containsString("class=\"input-action-field\"")))
                .andExpect(content().string(Matchers.containsString("data-remote-remember-destination")))
                .andExpect(content().string(Matchers.containsString("remote-custom-headers")))
                .andExpect(content().string(Matchers.containsString("id=\"remoteCustomHeaders\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/admin/utils/remote-download/inspect"))))
                .andExpect(content().string(Matchers.containsString("<th>Route</th>")));
    }

    @Test
    void remoteDownloadActionsUseVersionedApi() throws Exception {
        mockMvc.perform(get("/api/v1/remote-downloads/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/remote-downloads/inspect/discard")
                        .with(csrf())
                        .param("requestId", "missing-inspection"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.notification.message")
                        .value("Remote download request identifier is invalid."));

        mockMvc.perform(post("/admin/utils/remote-download/inspect/discard")
                        .with(csrf())
                        .param("requestId", "missing-inspection"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/utils/remote-download/cancel")
                        .with(csrf())
                        .param("id", "missing-task"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/utils/remote-download/tasks"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoriesStayTableInGridViewAndExposeEnhancedSelectAll() throws Exception {
        String directory = "grid-directory-" + System.nanoTime();
        String filename = "grid-file-" + System.nanoTime() + ".txt";
        Files.createDirectories(ROOT.resolve(directory));
        Files.writeString(ROOT.resolve(filename), "grid");

        mockMvc.perform(get("/api/v1/fs/listing").param("view", "grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.view").value("grid"))
                .andExpect(jsonPath("$.directories[?(@.name == '" + directory + "')]").exists())
                .andExpect(jsonPath("$.entries[?(@.name == '" + filename + "')]").exists());
    }

    @Test
    void gridFileCardsUseCompactPreviewOnlyActions() throws Exception {
        String filename = "grid-card-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "grid");

        mockMvc.perform(get("/api/v1/fs/listing").param("view", "grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.view").value("grid"))
                .andExpect(jsonPath("$.entries[?(@.name == '" + filename + "')].extensionLabel")
                        .value("TXT"))
                .andExpect(jsonPath("$.entries[?(@.name == '" + filename + "')].detailUrl").exists());
    }

    @Test
    void filesPageRendersTransferControls() throws Exception {
        mockMvc.perform(get("/api/v1/files/transfer-buffer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(false))
                .andExpect(jsonPath("$.transferBuffer.count").value(0));
    }

    @Test
    void browserPreferenceResetUsesVersionedApiOnly() throws Exception {
        mockMvc.perform(post("/api/v1/browser-preferences/reset")
                        .with(csrf())
                        .param("target", "files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value("/files"));

        mockMvc.perform(post("/files/preferences/reset").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileBrowserViewPreferenceCanBeSavedWithoutReloadingTheListing() throws Exception {
        mockMvc.perform(post("/api/v1/browser-preferences/files/view")
                        .with(csrf())
                        .param("view", "grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("grid"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        Matchers.containsString("endervault.files.view=grid")));
    }

    @Test
    void legacyTransferBufferRoutesAreRemoved() throws Exception {
        mockMvc.perform(post("/files/transfer/buffer").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/transfer/buffer").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/transfer/paste").with(csrf()).param("operation", "copy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/transfer/clear").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/transfer/remove").with(csrf()).param("itemPath", "legacy.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void filesPageLoadsReactOwnedArchiveCreationUi() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/react/assets/files-")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/js/archive-create.js"))));
    }

    @Test
    void selectedItemsCanBeQueuedAsStoredZipArchive() throws Exception {
        String directory = "archive-create-" + System.nanoTime();
        Path source = Files.createDirectories(ROOT.resolve(directory));
        Files.writeString(source.resolve("note.txt"), "archive me", StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/files/archives")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-Requested-With", "fetch")
                        .param("path", directory)
                        .param("items", "note.txt")
                        .param("outputName", "saved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.task.type").value("ARCHIVE_CREATE"))
                .andExpect(jsonPath("$.redirectUrl").value("/files?path=" + directory));

        Path archive = source.resolve("saved.zip");
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!Files.exists(archive) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(archive).exists().isNotEmptyFile();
    }

    @Test
    void legacyArchiveMutationRoutesAreRemoved() throws Exception {
        mockMvc.perform(post("/files/archive/create").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/archive/extract").with(csrf()).param("path", "legacy.zip"))
                .andExpect(status().isNotFound());
    }

    @Test
    void selectedItemsCanBeMovedThroughTransferBuffer() throws Exception {
        String source = "transfer-source-" + System.nanoTime();
        String target = "transfer-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "move me");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.active").value(true));

        mockMvc.perform(post("/api/v1/files/transfer-buffer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "move")
                        .param("conflictPolicy", "ask"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task.type").value("FILE_MOVE"))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!Files.exists(ROOT.resolve(target).resolve("note.txt")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(ROOT.resolve(source).resolve("note.txt")).doesNotExist();
        assertThat(Files.readString(ROOT.resolve(target).resolve("note.txt"))).isEqualTo("move me");
    }

    @Test
    void transferPasteQueuesSnapshotAndClearsBuffer() throws Exception {
        String source = "transfer-partial-source-" + System.nanoTime();
        String target = "transfer-partial-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("ok.txt"), "move me");
        Files.writeString(ROOT.resolve(source).resolve("stale.txt"), "gone");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "ok.txt")
                        .param("items", "stale.txt"))
                .andExpect(status().isOk());

        Files.delete(ROOT.resolve(source).resolve("stale.txt"));

        mockMvc.perform(post("/api/v1/files/transfer-buffer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "move")
                        .param("conflictPolicy", "ask"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task.type").value("FILE_MOVE"))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!Files.exists(ROOT.resolve(target).resolve("ok.txt")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(ROOT.resolve(source).resolve("ok.txt")).doesNotExist();
        assertThat(Files.readString(ROOT.resolve(target).resolve("ok.txt"))).isEqualTo("move me");

        mockMvc.perform(get("/files")
                        .session(session)
                        .param("path", target))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("1 item(s) ready"))));
    }

    @Test
    void transferBufferCanBeUpdatedWithAjax() throws Exception {
        String source = "transfer-ajax-source-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "ajax");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(true))
                .andExpect(jsonPath("$.transferBuffer.count").value(1))
                .andExpect(jsonPath("$.transferBuffer.items[0].name").value("note.txt"));

        mockMvc.perform(post("/api/v1/files/transfer-buffer/remove")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("itemPath", source + "/note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.active").value(true));

        mockMvc.perform(post("/api/v1/files/transfer-buffer/clear")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));
    }

    @Test
    void transferBufferAcceptsFullPathsFromDifferentDirectories() throws Exception {
        String base = "transfer-search-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(base).resolve("alpha"));
        Files.createDirectories(ROOT.resolve(base).resolve("beta"));
        Files.writeString(ROOT.resolve(base).resolve("alpha/note.txt"), "alpha");
        Files.writeString(ROOT.resolve(base).resolve("beta/note.txt"), "beta");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .param("paths", base + "/alpha/note.txt")
                        .param("paths", base + "/beta/note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.count").value(2))
                .andExpect(jsonPath("$.transferBuffer.items[0].path").value(base + "/alpha/note.txt"))
                .andExpect(jsonPath("$.transferBuffer.items[1].path").value(base + "/beta/note.txt"));
    }

    @Test
    void fullPathSelectionCollapsesItemsCoveredBySelectedDirectory() throws Exception {
        String base = "transfer-collapse-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(base).resolve("docs"));
        Files.writeString(ROOT.resolve(base).resolve("docs/note.txt"), "note");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .param("paths", base + "/docs/note.txt")
                        .param("paths", base + "/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.count").value(1))
                .andExpect(jsonPath("$.transferBuffer.items[0].path").value(base + "/docs"));
    }

    @Test
    void selectionRequestRejectsMixedFullPathAndLegacyItemContracts() throws Exception {
        String base = "transfer-ambiguous-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(base));
        Files.writeString(ROOT.resolve(base).resolve("note.txt"), "note");

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(new MockHttpSession())
                        .with(csrf())
                        .param("path", base)
                        .param("items", "note.txt")
                        .param("paths", base + "/note.txt"))
                .andExpect(status().isForbidden());
    }

    @Test
    void selectedItemsCanBeCopiedThroughTransferBuffer() throws Exception {
        String source = "transfer-copy-source-" + System.nanoTime();
        String target = "transfer-copy-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "copy me");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/files/transfer-buffer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "copy")
                        .param("conflictPolicy", "ask"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task.type").value("FILE_COPY"));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!Files.exists(ROOT.resolve(target).resolve("note.txt")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(Files.readString(ROOT.resolve(source).resolve("note.txt"))).isEqualTo("copy me");
        assertThat(Files.readString(ROOT.resolve(target).resolve("note.txt"))).isEqualTo("copy me");
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/page-jump.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly-table")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly-file-card")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(directory)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)));
    }

    @Test
    void dashboardPageRendersSummaryPanels() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Task Manager")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("System Health")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Management")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Utils")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Shared links")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Activity logs")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Trash")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Metadata inspector")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote download")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/vpn\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Page archiving")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Storage remaining")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Outbound route")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VPN Egress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Proxy health")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Background tasks")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Register and remove trusted devices"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Configure activity notifications and test messages"))))
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
    void legacyOperationalFileRoutesAreRemovedBeforeRelease() throws Exception {
        mockMvc.perform(get("/files/dashboard"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/shares"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/logs"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/trash"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/remote-download"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/files/shares/revoke").with(csrf()).param("token", "legacy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/logs/delete").with(csrf()).param("file", "legacy.jsonl"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/trash/empty").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/remote-download").with(csrf()).param("url", "https://example.com/file.bin"))
                .andExpect(status().isNotFound());
    }

    @Test
    void trashAndActivityLogMutationsUseVersionedApis() throws Exception {
        String fileName = "api-trash-" + java.util.UUID.randomUUID() + ".txt";
        Files.writeString(ROOT.resolve(fileName), "trash api test");
        TrashRecord record = trashService.moveToTrash("", List.of(fileName)).getFirst();

        mockMvc.perform(get("/admin/trash"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/api/v1/trash/empty")))
                .andExpect(content().string(Matchers.containsString("/api/v1/trash/restore")))
                .andExpect(content().string(Matchers.containsString("/api/v1/trash/delete")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/admin/trash/empty"))));

        mockMvc.perform(post("/api/v1/trash/delete")
                        .with(csrf())
                        .param("id", record.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(post("/admin/trash/restore")
                        .with(csrf())
                        .param("id", "missing-trash-record"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/trash/delete")
                        .with(csrf())
                        .param("id", "missing-trash-record"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/trash/empty").with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/logs"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/activity-logs/delete")
                        .with(csrf())
                        .param("file", "activity-log.jsonl"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(post("/admin/logs/delete")
                        .with(csrf())
                        .param("file", "activity-log.jsonl"))
                .andExpect(status().isNotFound());
    }

    @Test
    void settingsPageHostsTheReactSettingsEntryPoint() throws Exception {
        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"settings-root\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/react/assets/settings-")));
    }

    @Test
    @WithAnonymousUser
    void reactBuildAssetsUseThePublicStaticResourcePolicy() throws Exception {
        String entryScript = viteAssetService.entry("src/settings/main.tsx").entryScript();

        mockMvc.perform(get(entryScript))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/javascript"));
    }

    @Test
    void settingsReadApisExposeEveryReactSection() throws Exception {
        mockMvc.perform(get("/api/v1/settings/general"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.browser.defaultView").isString())
                .andExpect(jsonPath("$.stickyNotes.defaultBackgroundColor").value("#1B3033"))
                .andExpect(jsonPath("$.fileTools.textAutoLoadMaxMib").isString())
                .andExpect(jsonPath("$.remoteDownload.connectTimeoutSeconds").isNumber())
                .andExpect(jsonPath("$.remoteDownload.workerThreads").isNumber());
        mockMvc.perform(get("/api/v1/settings/advanced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isArray())
                .andExpect(jsonPath("$.groups[0].fields[1].dependencies[0]").value("shareEnabled"))
                .andExpect(jsonPath("$.deployment").isArray());
        mockMvc.perform(get("/api/v1/settings/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.htmlMaxKib").isString());
        mockMvc.perform(get("/api/v1/settings/file-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimitMaxAdmissions").isNumber());
        mockMvc.perform(get("/api/v1/settings/vpn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthConnectTimeoutSeconds").isString());
        mockMvc.perform(get("/api/v1/settings/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSessions").isNumber());
        mockMvc.perform(get("/api/v1/settings/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").isString());
        mockMvc.perform(get("/api/v1/settings/telegram-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isArray());
        mockMvc.perform(get("/api/v1/settings/passkeys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials").isArray())
                .andExpect(jsonPath("$.rpId").isString());
    }

    @Test
    void legacySettingsMutationEndpointsAreUnavailable() throws Exception {
        for (String endpoint : List.of(
                "/admin/settings/account",
                "/admin/settings/bookmarks",
                "/admin/settings/advanced",
                "/admin/settings/general",
                "/admin/settings/file-requests",
                "/admin/settings/passkeys",
                "/admin/settings/sessions",
                "/admin/settings/vpn",
                "/admin/settings/telegram-alerts"
        )) {
            mockMvc.perform(get(endpoint)).andExpect(status().isNotFound());
            mockMvc.perform(post(endpoint).with(csrf())).andExpect(status().isNotFound());
        }

        mockMvc.perform(post("/admin/settings/telegram-alerts/test").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/settings/passkeys/register/options").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/settings/passkeys/register/finish")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/settings/passkeys/legacy/delete").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void vpnStatusPageRendersRuntimeDetailsInPanel() throws Exception {
        mockMvc.perform(get("/admin/vpn"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-status-url=\"/api/v1/vpn/status\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/vpn/refresh\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/vpn/connect\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/vpn/reconnect\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/vpn/disconnect\"")))
                .andExpect(content().string(Matchers.containsString("Connection Details")))
                .andExpect(content().string(Matchers.containsString("VPN public IP")))
                .andExpect(content().string(Matchers.containsString("Outbound route")))
                .andExpect(content().string(Matchers.containsString("vpn-detail-wide vpn-active-tasks")))
                .andExpect(content().string(Matchers.containsString("data-vpn-runtime=\"controlBadge\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<dt>Profile</dt>"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("vpn-status-metrics"))));
    }

    @Test
    void removedVpnRuntimeEndpointsAreNotAvailable() throws Exception {
        mockMvc.perform(get("/admin/vpn/status"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/vpn/refresh").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/vpn/connect").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/vpn/reconnect").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/vpn/disconnect").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void settingsPageRendersSidebarFavoritesAndSharedReactShell() throws Exception {
        String filename = "settings-favorite-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");
        favoriteService.toggle(filename);

        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-favorites-list")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/directory-tree.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/directory-picker.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/js/settings-form.js"))));
    }

    @Test
    void recentPageRendersVirtualDirectoryShell() throws Exception {
        String query = "unlikely-recent-query-" + System.nanoTime();

        mockMvc.perform(get("/files/recent"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"recent-root\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Loading recent items...")));

        mockMvc.perform(get("/api/v1/recent").param("q", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search.query").value(query))
                .andExpect(jsonPath("$.search.performed").value(true))
                .andExpect(jsonPath("$.directories").isEmpty())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void bookmarkMutationsUseVersionedApi() throws Exception {
        String title = "API bookmark directory " + java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/bookmarks/directories")
                        .with(csrf())
                        .param("title", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value("/files/bookmarks"));

        BookmarkItem created = bookmarkService.list(null, title).stream()
                .filter(item -> title.equals(item.title()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/files/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"bookmarks-root\"")))
                .andExpect(content().string(Matchers.containsString("/react/assets/bookmarks-")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("bookmark-actions.js"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("bookmark-context-menu.js"))));

        mockMvc.perform(get("/api/v1/bookmarks").param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search.query").value(title))
                .andExpect(jsonPath("$.search.performed").value(true))
                .andExpect(jsonPath("$.directories[0].id").value(created.id()))
                .andExpect(jsonPath("$.directories[0].type").value("directory"))
                .andExpect(jsonPath("$.directories[0].primaryUrl").value(
                        "/files/bookmarks?directory=" + created.id()))
                .andExpect(jsonPath("$.directories[0].directory").doesNotExist())
                .andExpect(jsonPath("$.directories[0].link").doesNotExist())
                .andExpect(jsonPath("$.links").isEmpty());

        mockMvc.perform(post("/api/v1/bookmarks/delete")
                        .with(csrf())
                        .param("id", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value("/files/bookmarks"));

        mockMvc.perform(post("/api/v1/bookmarks/delete-selected").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(post("/files/bookmarks/directories")
                        .with(csrf())
                        .param("title", title))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/bookmarks/delete")
                        .with(csrf())
                        .param("id", created.id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/bookmarks/metadata")
                        .with(csrf())
                        .param("id", created.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void recentPageRecordsDetailAccessAndSearchesRecentItems() throws Exception {
        String filename = "recent-ui-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "recent");

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/recent").param("q", "recent-ui"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value(filename))
                .andExpect(jsonPath("$.entries[0].accessedAt").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].accessedLabel").isNotEmpty())
                .andExpect(jsonPath("$.search.performed").value(true));

        mockMvc.perform(post("/api/v1/recent/remove")
                        .with(csrf())
                        .param("paths", filename)
                        .param("q", "recent-ui"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value("/files/recent?q=recent-ui"));
        mockMvc.perform(post("/files/recent/remove")
                        .with(csrf())
                        .param("paths", filename))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/recent/clear").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void favoritesCanBeToggledAndManaged() throws Exception {
        String filename = "favorite-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");

        mockMvc.perform(post("/api/v1/favorites/toggle")
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/files/favorites"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Move up")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/favorites/move")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/favorites/remove")));

        mockMvc.perform(get("/api/v1/fs/listing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.name == '" + filename + "')].favorite")
                        .value(true));
    }

    @Test
    void favoriteToggleCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "favorite-json-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");

        mockMvc.perform(post("/api/v1/favorites/toggle")
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.favorite.path").value(filename))
                .andExpect(jsonPath("$.notification.type").value("success"));

        mockMvc.perform(post("/files/favorites/toggle")
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/favorites/remove")
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/favorites/move")
                        .with(csrf())
                        .param("path", filename)
                        .param("direction", "up"))
                .andExpect(status().isNotFound());
    }

    @Test
    void remoteDownloadPageRendersForm() throws Exception {
        mockMvc.perform(get("/admin/utils/remote-download"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote Download")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"url\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"path\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"conflictPolicy\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"skipInspection\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-remote-import-curl")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/remote-download-curl.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"curlCommand\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote download")));
    }

    @Test
    void metadataInspectorRendersScanAreasAsCompactRows() throws Exception {
        mockMvc.perform(get("/admin/metadata"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Metadata Inspector")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/metadata/scan")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/admin/metadata/scan"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/admin/metadata/repair"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("metadata-area-list")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("metadata-area-row")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("File requests")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pending decisions")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("metadata-area-grid"))));
    }

    @Test
    void metadataRepairUsesVersionedJsonApi() throws Exception {
        mockMvc.perform(post("/api/v1/metadata/repair")
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("repairAll", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("info"));
    }

    @Test
    void removedMetadataMutationEndpointsAreNotAvailable() throws Exception {
        mockMvc.perform(post("/admin/metadata/scan").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/metadata/repair").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logsPageRendersActivityEntries() throws Exception {
        String directory = "log-dir-" + System.nanoTime();

        mockMvc.perform(post("/api/v1/files/directories")
                        .with(csrf())
                        .param("name", directory))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/admin/logs")
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
    void fileMutationFailuresStayJson() throws Exception {
        Files.createDirectories(ROOT.resolve("existing"));

        mockMvc.perform(post("/api/v1/files/directories")
                        .with(csrf())
                        .param("name", "existing"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.notification.type").value("error"));
    }

    @Test
    void legacyFileMutationRoutesAreRemoved() throws Exception {
        mockMvc.perform(post("/files/directories").with(csrf()).param("name", "legacy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/files").with(csrf()).param("name", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/rename").with(csrf())
                        .param("item", "legacy.txt")
                        .param("newName", "renamed.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/move").with(csrf())
                        .param("item", "legacy.txt")
                        .param("targetPath", "target"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/delete").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shareCreationCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "ajax-share-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "share");

        mockMvc.perform(post("/api/v1/shares")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename)
                        .param("customToken", "ajax-share-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("info"))
                .andExpect(jsonPath("$.notification.actionValue").exists())
                .andExpect(jsonPath("$.shareLink.token").exists())
                .andExpect(jsonPath("$.shareLink.url").exists())
                .andExpect(jsonPath("$.shareLink.directDownloadUrl").value(
                        Matchers.containsString("/download/" + filename)));
    }

    @Test
    void legacyFileShareMutationRoutesAreRemoved() throws Exception {
        mockMvc.perform(post("/files/share").with(csrf()).param("item", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/share").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/shares/revoke").with(csrf()).param("token", "legacy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/shares/delete").with(csrf()).param("token", "legacy"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sharedLinksPageOffersCopyActionsForFileSharesOnly() throws Exception {
        String filename = "copy-link-" + System.nanoTime() + ".txt";
        String directory = "copy-link-dir-" + System.nanoTime();
        Files.writeString(ROOT.resolve(filename), "share");
        Files.createDirectories(ROOT.resolve(directory));
        ShareLink fileShare = shareLinkService.create("", filename, null);
        ShareLink directoryShare = shareLinkService.create("", directory, null);

        mockMvc.perform(get("/admin/shares"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Copy link")))
                .andExpect(content().string(Matchers.containsString("Copy direct download link")))
                .andExpect(content().string(Matchers.containsString("/api/v1/shares/revoke")))
                .andExpect(content().string(Matchers.containsString("/api/v1/shares/delete")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/admin/shares/revoke"))))
                .andExpect(content().string(Matchers.containsString(
                        "/s/" + fileShare.token() + "/download/" + filename)))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                         "/s/" + directoryShare.token() + "/download/" + directory))));
    }

    @Test
    void sharedLinkMutationsUseVersionedApiOnly() throws Exception {
        String filename = "share-api-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "share");
        ShareLink shareLink = shareLinkService.create("", filename, null);

        mockMvc.perform(post("/api/v1/shares/revoke")
                        .with(csrf())
                        .param("token", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.redirectUrl").value(Matchers.nullValue()));

        assertThat(shareLinkService.list())
                .filteredOn(candidate -> candidate.token().equals(shareLink.token()))
                .singleElement()
                .extracting(ShareLink::enabled)
                .isEqualTo(false);

        mockMvc.perform(post("/api/v1/shares/delete")
                        .with(csrf())
                        .param("token", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(shareLinkService.list()).noneMatch(candidate -> candidate.token().equals(shareLink.token()));
        mockMvc.perform(post("/admin/shares/revoke").with(csrf()).param("token", shareLink.token()))
                .andExpect(status().isNotFound());
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
    void imageDetailRendersEnhancedViewerWithFallbackImage() throws Exception {
        String filename = "viewer-image-" + System.nanoTime() + ".jpg";
        Files.write(ROOT.resolve(filename), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-image-viewer")))
                .andExpect(content().string(Matchers.containsString("data-image-viewer-source")))
                .andExpect(content().string(Matchers.containsString("data-image-action=\"zoom-in\"")))
                .andExpect(content().string(Matchers.containsString(
                        "/webjars/viewerjs/1.11.7/dist/viewer.min.css"
                )))
                .andExpect(content().string(Matchers.containsString(
                        "/webjars/viewerjs/1.11.7/dist/viewer.min.js"
                )))
                .andExpect(content().string(Matchers.containsString("/js/image-viewer.js")));
    }

    @Test
    void audioDetailAndSharedLandingReuseNativeAudioPlayer() throws Exception {
        String filename = "shared-audio-" + System.nanoTime() + ".mp3";
        Files.write(ROOT.resolve(filename), new byte[] {0x49, 0x44, 0x33, 0x04});
        ShareLink shareLink = shareLinkService.create("", filename, null);

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Audio Player")))
                .andExpect(content().string(Matchers.containsString("class=\"audio-tool-player\"")))
                .andExpect(content().string(Matchers.containsString("preload=\"metadata\"")))
                .andExpect(content().string(Matchers.containsString("/files/preview?item=")));

        mockMvc.perform(get("/s/{token}", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Audio Player")))
                .andExpect(content().string(Matchers.containsString("class=\"audio-tool-player\"")))
                .andExpect(content().string(Matchers.containsString("/s/" + shareLink.token() + "/preview")));
    }

    @Test
    void sharedFileLandingRendersTextPreviewEscaped() throws Exception {
        String filename = "shared-text-" + System.nanoTime() + ".html";
        Files.writeString(ROOT.resolve(filename), "<script>alert(1)</script>", StandardCharsets.UTF_8);
        ShareLink shareLink = shareLinkService.create("", filename, null);

        MvcResult result = mockMvc.perform(get("/s/{token}", shareLink.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("/react/assets/fileTools-")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/webjars/codemirror/"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/js/file-tools.js"))))
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
        assertThat(result.getResponse().getContentAsString()).contains("/react/assets/fileTools-");
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
                .andExpect(content().string(Matchers.containsString("action=\"/api/v1/files/text/save\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("before")));

        mockMvc.perform(post("/api/v1/files/text/save")
                        .with(csrf())
                        .param("path", filename)
                        .param("editorToken", "editor-" + System.nanoTime())
                        .param("content", "after"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("success"));

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

        mockMvc.perform(get("/api/v1/files/text/load")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.text.editable").value(true))
                .andExpect(jsonPath("$.text.content", Matchers.startsWith("large-text-")));
    }

    @Test
    void legacyTextEditorRoutesAreRemoved() throws Exception {
        mockMvc.perform(post("/files/detail/text").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/detail/text/load").param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/detail/text/draft").param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/text/draft").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/text/draft/save-as").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/text/draft/restore").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/files/detail/text/draft/discard").with(csrf()).param("path", "legacy.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void comicDetailPageLoadsComicViewerWithoutTextEditorAssets() throws Exception {
        String filename = "comic-detail-" + System.nanoTime() + ".cbz";
        writeComicStub(ROOT.resolve(filename));

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-comic-page-url")))
                .andExpect(content().string(Matchers.containsString("/js/comic-viewer.js")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/js/file-tools.js"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/webjars/codemirror/"))));
    }

    @Test
    void adminUploadUsesResumableProtocolAndFinalizesIntoVault() throws Exception {
        String filename = "ajax-upload-" + System.nanoTime() + ".txt";
        byte[] content = "upload".getBytes(StandardCharsets.UTF_8);

        JsonNode result = admitAndUpload(
                "/api/v1/files/upload-sessions",
                filename,
                null,
                content,
                "c".repeat(64)
        );

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(ROOT.resolve(filename))).isEqualTo(content);
    }

    @Test
    void legacyUploadConflictRouteIsRemoved() throws Exception {
        mockMvc.perform(post("/files/upload/conflicts/resolve").with(csrf()).param("id", "legacy"))
                .andExpect(status().isNotFound());
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
    void mixedParentDownloadPreservesPathsRelativeToSearchRoot() throws Exception {
        String base = "selected-search-zip-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(base).resolve("alpha"));
        Files.createDirectories(ROOT.resolve(base).resolve("beta"));
        Files.writeString(ROOT.resolve(base).resolve("alpha/note.txt"), "alpha");
        Files.writeString(ROOT.resolve(base).resolve("beta/note.txt"), "beta");

        MvcResult result = mockMvc.perform(get("/files/download.zip")
                        .param("path", base)
                        .param("paths", base + "/alpha/note.txt")
                        .param("paths", base + "/beta/note.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, Matchers.containsString("application/zip")))
                .andReturn();

        Set<String> entries = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).containsExactly("alpha/note.txt", "beta/note.txt");
    }

    @Test
    void deleteSelectedCanReturnJsonForEnhancedForms() throws Exception {
        String filename = "ajax-delete-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "delete");

        mockMvc.perform(post("/api/v1/files/trash")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("items", filename))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("info"))
                .andExpect(jsonPath("$.task.id").exists())
                .andExpect(jsonPath("$.task.type").value("FILE_TRASH"))
                .andExpect(jsonPath("$.redirectUrl").exists());
    }

    @Test
    void fullPathSelectionCanMoveItemsFromDifferentDirectoriesToTrash() throws Exception {
        String base = "trash-search-" + System.nanoTime();
        Path first = ROOT.resolve(base).resolve("alpha/note.txt");
        Path second = ROOT.resolve(base).resolve("beta/note.txt");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "alpha");
        Files.writeString(second, "beta");

        mockMvc.perform(post("/api/v1/files/trash")
                        .with(csrf())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .param("path", base)
                        .param("paths", base + "/alpha/note.txt")
                        .param("paths", base + "/beta/note.txt"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task.type").value("FILE_TRASH"));

        long deadline = System.currentTimeMillis() + 5_000L;
        while ((Files.exists(first) || Files.exists(second)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
    }

    @Test
    void detailRenameCanReturnJsonRedirectForEnhancedForms() throws Exception {
        String filename = "ajax-rename-" + System.nanoTime() + ".txt";
        String renamed = "ajax-renamed-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "rename");

        mockMvc.perform(post("/api/v1/files/detail/rename")
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

    @Test
    void detailPageUsesTransferBufferManageAction() throws Exception {
        String filename = "detail-manage-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "manage");

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Add to transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Move to trash")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/file-transfer-buffer.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/v1/files/transfer-buffer/detail")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Danger zone"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"targetPath\""))));
    }

    @Test
    void detailItemCanBeAddedToTransferBuffer() throws Exception {
        String filename = "detail-buffer-" + System.nanoTime() + ".txt";
        String target = "detail-buffer-target-" + System.nanoTime();
        Files.writeString(ROOT.resolve(filename), "buffer");
        Files.createDirectories(ROOT.resolve(target));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer/detail")
                        .session(session)
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.active").value(true));

        mockMvc.perform(get("/files/detail")
                        .session(session)
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Move here"))));

        mockMvc.perform(post("/api/v1/files/transfer-buffer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "copy")
                        .param("conflictPolicy", "ask"))
                .andExpect(status().isAccepted());

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!Files.exists(ROOT.resolve(target).resolve(filename)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(Files.readString(ROOT.resolve(target).resolve(filename))).isEqualTo("buffer");
    }

    @Test
    void directoryDetailCanUseTransferBufferAsPasteTarget() throws Exception {
        String filename = "detail-buffer-target-file-" + System.nanoTime() + ".txt";
        String target = "detail-buffer-target-dir-" + System.nanoTime();
        Files.writeString(ROOT.resolve(filename), "buffer");
        Files.createDirectories(ROOT.resolve(target));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/v1/files/transfer-buffer/detail")
                        .session(session)
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().isOk());

        mockMvc.perform(get("/files/detail")
                        .session(session)
                        .param("path", target))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Move here")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Copy here")));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-notifications-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }

    private static void writeComicStub(Path path) throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(path))) {
            zipOutputStream.putNextEntry(new ZipEntry("001.png"));
            zipOutputStream.write(new byte[] {1, 2, 3});
            zipOutputStream.closeEntry();
        }
    }
}
