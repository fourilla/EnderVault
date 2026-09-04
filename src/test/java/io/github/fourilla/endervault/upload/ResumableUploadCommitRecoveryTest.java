package io.github.fourilla.endervault.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumableUploadCommitRecoveryTest {

    @TempDir
    Path root;

    private ResumableUploadRepository repository;
    private ResumableUploadService uploadService;
    private FileCommitCoordinator commitCoordinator;
    private FileCommitJournalStore journalStore;
    private StorageService storageService;

    @BeforeEach
    void setUp() throws IOException {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        storageService = new StorageService(properties);
        storageService.initialize();
        repository = new ResumableUploadRepository(objectMapper, properties);
        repository.initialize();
        journalStore = new FileCommitJournalStore(objectMapper, properties);
        journalStore.initialize();
        commitCoordinator = new FileCommitCoordinator(journalStore, storageService, properties);
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
