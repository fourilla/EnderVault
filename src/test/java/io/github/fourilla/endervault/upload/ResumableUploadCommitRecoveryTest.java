package io.github.fourilla.endervault.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResumableUploadCommitRecoveryTest {

    @TempDir
    Path root;

    private ResumableUploadRepository repository;
    private ResumableUploadService uploadService;
    private FileCommitCoordinator commitCoordinator;
    private FileCommitJournalStore journalStore;
    private StorageService storageService;
    private NasProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        storageService = new StorageService(properties);
        storageService.initialize();
        repository = new ResumableUploadRepository(objectMapper, properties);
        repository.initialize();
        journalStore = new FileCommitJournalStore(objectMapper, properties);
        journalStore.initialize();
        commitCoordinator = spy(new FileCommitCoordinator(journalStore, storageService, properties));
        PendingFileDecisionService pendingService = mock(PendingFileDecisionService.class);
        when(pendingService.findBySourceReference(any(), anyString())).thenReturn(Optional.empty());
        uploadService = new ResumableUploadService(
                repository,
                mock(FileRequestService.class),
                storageService,
                pendingService,
                commitCoordinator,
                properties
        );
    }

    @Test
    void resumesWhenFileCommitFinishedBeforeUploadSessionRecordedItsTarget() throws IOException {
        ResumableUploadSession session = stagedSession("payload");
        Path staged = storageService.resolveFileStagingFile(session.stagingFilename());
        ResumableUploadSession finalizing = session.finalizing();
        repository.save(finalizing);
        FileCommitOwner owner = new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, session.id());
        commitCoordinator.commitSingleFile(owner, staged, "", session.originalFilename());

        assertThatThrownBy(() -> uploadService.cancel(session.id()))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("still being recovered");

        ResumableUploadService.FinalizationResult result = uploadService.finalizeStaged(session.id());

        assertThat(result.status()).isEqualTo(ResumableUploadStatus.COMPLETED);
        assertThat(result.committedPath()).isEqualTo("upload.txt");
        assertThat(Files.readString(root.resolve("upload.txt"))).isEqualTo("payload");
        assertThat(repository.find(session.id()).orElseThrow().status())
                .isEqualTo(ResumableUploadStatus.COMPLETED);
        assertThat(journalStore.list()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admittedUploadRetainsPolicyAcrossStatusChangesAndFinalization(boolean admittedPolicy) throws IOException {
        boolean effectivePolicy = FileSystemOperations.operation(admittedPolicy).nativeEnabled();
        AtomicBoolean publicationPolicy = new AtomicBoolean(!effectivePolicy);
        doAnswer(invocation -> {
            publicationPolicy.set(FileSystemOperations.operationOrCapture().nativeEnabled());
            return invocation.callRealMethod();
        }).when(commitCoordinator).commitSingleFile(any(), any(), anyString(), anyString());
        ResumableUploadSession admitted;
        try (var ignored = FileSystemOperations.operation(admittedPolicy).activate()) {
            admitted = uploadService.admitAdmin("", "upload.txt", "text/plain", 7, "a".repeat(64), null);
        }
        assertThat(admitted.nativeEnabled()).isEqualTo(effectivePolicy);
        Path staged = storageService.resumableUploadStagingFile(admitted.id());
        Files.writeString(staged, "payload");
        uploadService.markStaged(admitted.id(), staged);

        try (var ignored = FileSystemOperations.operation(!admittedPolicy).activate()) {
            assertThat(uploadService.filesystemOperation(admitted.id()).nativeEnabled()).isEqualTo(effectivePolicy);
            var result = uploadService.finalizeStaged(admitted.id());
            assertThat(result.status()).isEqualTo(ResumableUploadStatus.COMPLETED);
        }
        assertThat(repository.find(admitted.id()).orElseThrow().nativeEnabled()).isEqualTo(effectivePolicy);
        assertThat(publicationPolicy.get()).isEqualTo(effectivePolicy);
        assertThat(root.resolve("upload.txt")).hasContent("payload");
    }

    @Test
    void legacyUploadCapturesAndPersistsPolicyBeforeFinalization() throws IOException {
        ResumableUploadSession legacy = stagedSession("payload");
        assertThat(legacy.nativeEnabled()).isNull();
        try (var ignored = FileSystemOperations.operation(false).activate()) {
            assertThat(uploadService.filesystemOperation(legacy.id()).nativeEnabled()).isFalse();
        }
        assertThat(repository.find(legacy.id()).orElseThrow().nativeEnabled()).isFalse();
        try (var ignored = FileSystemOperations.operation(true).activate()) {
            uploadService.finalizeStaged(legacy.id());
        }
        assertThat(repository.find(legacy.id()).orElseThrow().nativeEnabled()).isFalse();
        assertThat(root.resolve("upload.txt")).hasContent("payload");
    }

    @Test
    void readsLegacySessionWithoutFilesystemPolicyField() {
        ResumableUploadSession legacy = new ResumableUploadSession("id", ResumableUploadSource.ADMIN,
                "admin", "", "upload.txt", "text/plain", 7, null, "a".repeat(64), Instant.now(),
                Instant.now().plusSeconds(3600), ResumableUploadStatus.ADMITTED,
                null, null, null, null, null);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(legacy);
        tree.remove("nativeEnabled");
        String json = mapper.writeValueAsString(tree);
        assertThat(json).doesNotContain("nativeEnabled");
        assertThat(mapper.readValue(json, ResumableUploadSession.class).nativeEnabled()).isNull();
    }

    @Test
    void recoveredCoordinatorLogsActualPublicationUsingTheAdmittedPolicy() throws IOException {
        ResumableUploadSession admitted;
        try (var ignored = FileSystemOperations.operation(false).activate()) {
            admitted = uploadService.admitAdmin("", "upload.txt", "text/plain", 7, "a".repeat(64), null);
        }
        Path staged = storageService.resumableUploadStagingFile(admitted.id());
        Files.writeString(staged, "payload");
        uploadService.markStaged(admitted.id(), staged);
        ActivityLogService activity = activityLog();
        ResumableUploadCoordinator coordinator = new ResumableUploadCoordinator(uploadService,
                mock(ResumableUploadProtocolService.class), activity, properties);

        try (var ignored = FileSystemOperations.operation(true).activate()) {
            coordinator.cleanupAndRecover();
        }

        var entries = activity.recentCurrentEntries(20).stream()
                .filter(entry -> "UPLOAD".equals(entry.type())).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().metadataView())
                .containsEntry("fs.nativeEnabled", "false")
                .containsEntry("fs.backend", "JAVA")
                .containsEntry("fs.jnaOperations", "0");
        assertThat(root.resolve("upload.txt")).hasContent("payload");
    }

    @Test
    void recoveringAnAlreadyPublishedUploadDoesNotInventAJavaPublication() throws IOException {
        ResumableUploadSession session = stagedSession("payload").withNativeEnabled(false);
        Path staged = storageService.resolveFileStagingFile(session.stagingFilename());
        FileCommitOwner owner = new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, session.id());
        commitCoordinator.commitSingleFile(owner, staged, "", session.originalFilename());
        repository.save(session.finalizing().withCommittedTarget("upload.txt"));
        ActivityLogService activity = activityLog();
        ResumableUploadCoordinator coordinator = new ResumableUploadCoordinator(uploadService,
                mock(ResumableUploadProtocolService.class), activity, properties);

        coordinator.cleanupAndRecover();

        var entries = activity.recentCurrentEntries(20).stream()
                .filter(entry -> "UPLOAD".equals(entry.type())).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().metadataView())
                .containsEntry("fs.backend", "UNKNOWN")
                .containsEntry("fs.recoveredOperations", "1")
                .containsEntry("fs.jnaOperations", "0")
                .containsEntry("fs.javaOperations", "0");
        assertThat(root.resolve("upload.txt")).hasContent("payload");
    }

    private ActivityLogService activityLog() throws IOException {
        ActivityLogService activity = new ActivityLogService(JsonMapper.builder().findAndAddModules().build(), properties);
        activity.initialize();
        return activity;
    }

    private ResumableUploadSession stagedSession(String content) throws IOException {
        String id = UUID.randomUUID().toString();
        Path staged = storageService.resumableUploadStagingFile(id);
        Files.writeString(staged, content);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ResumableUploadSession session = new ResumableUploadSession(
                id,
                ResumableUploadSource.ADMIN,
                "admin",
                "",
                "upload.txt",
                "text/plain",
                Files.size(staged),
                null,
                "a".repeat(64),
                now,
                now.plusSeconds(3600),
                ResumableUploadStatus.STAGED,
                null,
                staged.getFileName().toString(),
                null,
                null,
                null
        );
        repository.save(session);
        return session;
    }
}
