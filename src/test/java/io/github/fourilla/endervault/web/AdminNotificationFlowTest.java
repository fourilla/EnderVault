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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadRepository;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadSource;
import io.github.fourilla.endervault.upload.ResumableUploadStatus;
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
    FileRequestService fileRequestService;

    @Autowired
    ResumableUploadService resumableUploadService;

    @Autowired
    ResumableUploadRepository resumableUploadRepository;

    @Autowired
    StorageService storageService;

    @Autowired
    ObjectMapper objectMapper;

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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"toastRegion\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-notification-center")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/notification-center.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-file-requests-enabled=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-outbound-route-form")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/page-jump.js")))
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
                .andExpect(content().string(Matchers.containsString("Upload final assets only.")))
                .andExpect(content().string(Matchers.containsString("Active Uploads")))
                .andExpect(content().string(Matchers.containsString("Pending Files")))
                .andExpect(content().string(Matchers.containsString(
                        "/api/v1/file-requests/" + request.id() + "/revoke"
                )))
                .andExpect(content().string(Matchers.containsString("copyFrom=" + request.id())));
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
                .andExpect(content().string(Matchers.containsString("data-discard-url=\"/admin/utils/remote-download/inspect/discard\"")))
                .andExpect(content().string(Matchers.containsString("id=\"remoteCurlDialog\"")))
                .andExpect(content().string(Matchers.containsString("data-storage-directory-picker")))
                .andExpect(content().string(Matchers.containsString("/js/directory-tree.js")))
                .andExpect(content().string(Matchers.containsString("class=\"input-action-field\"")))
                .andExpect(content().string(Matchers.containsString("data-remote-remember-destination")))
                .andExpect(content().string(Matchers.containsString("remote-custom-headers")))
                .andExpect(content().string(Matchers.containsString("id=\"remoteCustomHeaders\"")))
                .andExpect(content().string(Matchers.containsString("<th>Route</th>")));
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
    void filesPageRendersTransferControls() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"addToTransferBufferButton\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-transfer-action=\"transfer-buffer-add\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-selection-required")));
    }

    @Test
    void filesPageRendersSharedZipCreationDialogAndScript() throws Exception {
        mockMvc.perform(get("/files"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"compressSelectedButton\"")))
                .andExpect(content().string(Matchers.containsString("id=\"archiveCreationDialog\"")))
                .andExpect(content().string(Matchers.containsString("action=\"/files/archive/create\"")))
                .andExpect(content().string(Matchers.containsString("/js/archive-create.js")));
    }

    @Test
    void selectedItemsCanBeQueuedAsStoredZipArchive() throws Exception {
        String directory = "archive-create-" + System.nanoTime();
        Path source = Files.createDirectories(ROOT.resolve(directory));
        Files.writeString(source.resolve("note.txt"), "archive me", StandardCharsets.UTF_8);

        mockMvc.perform(post("/files/archive/create")
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
    void selectedItemsCanBeMovedThroughTransferBuffer() throws Exception {
        String source = "transfer-source-" + System.nanoTime();
        String target = "transfer-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "move me");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/files/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?path=" + source));

        mockMvc.perform(post("/files/transfer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "move"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?path=" + target));

        assertThat(ROOT.resolve(source).resolve("note.txt")).doesNotExist();
        assertThat(Files.readString(ROOT.resolve(target).resolve("note.txt"))).isEqualTo("move me");
    }

    @Test
    void transferPasteProcessesValidItemsAndKeepsFailedItemsInBuffer() throws Exception {
        String source = "transfer-partial-source-" + System.nanoTime();
        String target = "transfer-partial-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("ok.txt"), "move me");
        Files.writeString(ROOT.resolve(source).resolve("stale.txt"), "gone");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/files/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "ok.txt")
                        .param("items", "stale.txt"))
                .andExpect(status().is3xxRedirection());

        Files.delete(ROOT.resolve(source).resolve("stale.txt"));

        mockMvc.perform(post("/files/transfer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "move"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?path=" + target))
                .andExpect(flash().attributeExists(FlashNotifications.ATTRIBUTE_NAME));

        assertThat(ROOT.resolve(source).resolve("ok.txt")).doesNotExist();
        assertThat(Files.readString(ROOT.resolve(target).resolve("ok.txt"))).isEqualTo("move me");

        mockMvc.perform(get("/files")
                        .session(session)
                        .param("path", target))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stale.txt")));
    }

    @Test
    void transferBufferCanBeUpdatedWithAjax() throws Exception {
        String source = "transfer-ajax-source-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "ajax");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/files/transfer/buffer")
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

        mockMvc.perform(post("/files/transfer/remove")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("path", source)
                        .param("itemPath", source + "/note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));

        mockMvc.perform(post("/files/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferBuffer.active").value(true));

        mockMvc.perform(post("/files/transfer/clear")
                        .session(session)
                        .with(csrf())
                        .header("X-Requested-With", "fetch")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("path", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.transferBuffer.active").value(false));
    }

    @Test
    void selectedItemsCanBeCopiedThroughTransferBuffer() throws Exception {
        String source = "transfer-copy-source-" + System.nanoTime();
        String target = "transfer-copy-target-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(source));
        Files.createDirectories(ROOT.resolve(target));
        Files.writeString(ROOT.resolve(source).resolve("note.txt"), "copy me");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/files/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", source)
                        .param("items", "note.txt"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/files/transfer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "copy"))
                .andExpect(status().is3xxRedirection());

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
    void settingsPageLinksToSecurityAndNotificationSettings() throws Exception {
        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("settings-index-panel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("settings-link-row")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Application")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Security")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Notifications")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passkeys")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Telegram alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("General settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VPN egress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/passkeys")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/telegram-alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/general")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/vpn")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Metadata inspector"))));
    }

    @Test
    void generalSettingsPageRendersApplicationSettingsForm() throws Exception {
        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("General Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Browser Defaults")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Recent Items")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"sticky-note-theme\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"stickyNoteBackgroundColor\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"stickyNoteBorderColor\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"stickyNoteTextColor\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/sticky-note-theme-settings.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("--sticky-note-bg: #1B3033")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Trash")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("File Tools")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Remote Download")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"remoteDefaultTargetDirectory\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/directory-tree.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/directory-picker.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"general-settings-save\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/general")));
    }

    @Test
    void vpnSettingsPageRendersConfigurationAndStatusLink() throws Exception {
        mockMvc.perform(get("/admin/settings/vpn"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VPN Egress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Proxy Connection")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Tunnel Health")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"vpn-settings-save\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/vpn\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Current State")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("vpn-health-refresh")
                )));
    }

    @Test
    void vpnStatusPageRendersRuntimeDetailsInPanel() throws Exception {
        mockMvc.perform(get("/admin/vpn"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Connection Details")))
                .andExpect(content().string(Matchers.containsString("VPN public IP")))
                .andExpect(content().string(Matchers.containsString("Outbound route")))
                .andExpect(content().string(Matchers.containsString("vpn-detail-wide vpn-active-tasks")))
                .andExpect(content().string(Matchers.containsString("data-vpn-runtime=\"controlBadge\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<dt>Profile</dt>"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("vpn-status-metrics"))));
    }

    @Test
    void settingsPagesRenderSidebarFavorites() throws Exception {
        String filename = "settings-favorite-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "favorite");
        favoriteService.toggle(filename);

        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-favorites-list")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)));

        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-favorites-list")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)));
    }

    @Test
    void passkeysPageRendersSettingsScopedActions() throws Exception {
        mockMvc.perform(get("/admin/settings/passkeys"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Passkeys")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Register Device")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("settings-detail-panel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/passkeys/register/options")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/passkeys/register/finish")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Back to settings")));
    }

    @Test
    void telegramAlertsPageRendersSettingsForm() throws Exception {
        mockMvc.perform(get("/admin/settings/telegram-alerts"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Telegram Alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enable Telegram alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("settings-switch-input")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("form=\"telegramSettingsForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-toggle-target=\".telegram-settings-dependent\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bot token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chat ID")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LOGIN_SUCCESS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save and Apply")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Send Test Message")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-password-toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Show bot token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/admin-actions.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/telegram-alerts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/telegram-alerts/test")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"telegram-settings-save\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-action=\"telegram-settings-test\"")));
    }

    @Test
    void recentPageRendersVirtualDirectoryShell() throws Exception {
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("metadata-area-list")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("metadata-area-row")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("File requests")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pending decisions")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("metadata-area-grid"))));
    }

    @Test
    void logsPageRendersActivityEntries() throws Exception {
        String directory = "log-dir-" + System.nanoTime();

        mockMvc.perform(post("/files/directories")
                        .with(csrf())
                        .param("name", directory))
                .andExpect(status().is3xxRedirection());

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
                .andExpect(jsonPath("$.shareLink.token").exists())
                .andExpect(jsonPath("$.shareLink.url").exists())
                .andExpect(jsonPath("$.shareLink.directDownloadUrl").value(
                        Matchers.containsString("/download/" + filename)));
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
                .andExpect(content().string(Matchers.containsString(
                        "/s/" + fileShare.token() + "/download/" + filename)))
                .andExpect(content().string(Matchers.not(Matchers.containsString(
                        "/s/" + directoryShare.token() + "/download/" + directory))));
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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notification.type").value("info"))
                .andExpect(jsonPath("$.task.id").exists())
                .andExpect(jsonPath("$.task.type").value("FILE_TRASH"))
                .andExpect(jsonPath("$.redirectUrl").exists());
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

    @Test
    void detailPageUsesTransferBufferManageAction() throws Exception {
        String filename = "detail-manage-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(filename), "manage");

        mockMvc.perform(get("/files/detail").param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Add to transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Move to trash")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/file-transfer-buffer.js")))
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

        mockMvc.perform(post("/files/detail/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/detail?path=" + filename))
                .andExpect(flash().attributeExists(FlashNotifications.ATTRIBUTE_NAME));

        mockMvc.perform(get("/files/detail")
                        .session(session)
                        .param("path", filename))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Transfer buffer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(filename)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Move here"))));

        mockMvc.perform(post("/files/transfer/paste")
                        .session(session)
                        .with(csrf())
                        .param("path", target)
                        .param("operation", "copy"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?path=" + target));

        assertThat(Files.readString(ROOT.resolve(target).resolve(filename))).isEqualTo("buffer");
    }

    @Test
    void directoryDetailCanUseTransferBufferAsPasteTarget() throws Exception {
        String filename = "detail-buffer-target-file-" + System.nanoTime() + ".txt";
        String target = "detail-buffer-target-dir-" + System.nanoTime();
        Files.writeString(ROOT.resolve(filename), "buffer");
        Files.createDirectories(ROOT.resolve(target));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/files/detail/transfer/buffer")
                        .session(session)
                        .with(csrf())
                        .param("path", filename))
                .andExpect(status().is3xxRedirection());

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
