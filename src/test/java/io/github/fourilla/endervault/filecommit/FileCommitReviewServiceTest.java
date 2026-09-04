package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommitReviewServiceTest {

    @TempDir
    Path tempDirectory;

    private Path journalRoot;
    private FileCommitJournalStore store;
    private FileCommitRecoveryIncidentRegistry incidentRegistry;
    private FileCommitReviewService service;

    @BeforeEach
    void setUp() throws IOException {
        journalRoot = tempDirectory.resolve("commit-journal");
        store = new FileCommitJournalStore(new ObjectMapper().findAndRegisterModules(), journalRoot);
        store.initialize();
        incidentRegistry = new FileCommitRecoveryIncidentRegistry();
        service = new FileCommitReviewService(store, incidentRegistry);
    }

    @Test
    void reportsOnlyJournalsThatNeedManualAttention() throws IOException {
        FileCommitManifest active = createManifest(FileCommitOwnerType.REMOTE_DOWNLOAD);
        FileCommitManifest needsReview = createManifest(FileCommitOwnerType.ARCHIVE_EXTRACT);
        FileCommitManifest aborted = createManifest(FileCommitOwnerType.PENDING_FILE_DECISION);
        store.create(active);
        store.create(needsReview);
        store.create(aborted);
        store.updateState(state(needsReview.operationId(), FileCommitPhase.NEEDS_REVIEW));
        store.updateState(state(aborted.operationId(), FileCommitPhase.ABORTED));

        List<FileCommitReviewItem> items = service.list();

        assertThat(items).extracting(FileCommitReviewItem::operationId)
                .containsExactlyInAnyOrder(needsReview.operationId(), aborted.operationId());
        assertThat(items).anySatisfy(item -> {
            assertThat(item.operationId()).isEqualTo(needsReview.operationId());
            assertThat(item.kind()).isEqualTo(FileCommitReviewKind.NEEDS_REVIEW);
            assertThat(item.ownerType()).isEqualTo(FileCommitOwnerType.ARCHIVE_EXTRACT);
        });
        assertThat(items).anySatisfy(item -> {
            assertThat(item.operationId()).isEqualTo(aborted.operationId());
            assertThat(item.kind()).isEqualTo(FileCommitReviewKind.ABORTED);
        });
    }

    @Test
    void reportsCorruptJournalWithoutRemovingIt() throws IOException {
        FileCommitManifest corrupt = createManifest(FileCommitOwnerType.REMOTE_DOWNLOAD);
        store.create(corrupt);
        Path state = journalRoot.resolve(corrupt.operationId()).resolve("state.json");
        Files.writeString(state, "{broken");

        assertThat(service.list()).singleElement().satisfies(item -> {
            assertThat(item.operationId()).isEqualTo(corrupt.operationId());
            assertThat(item.kind()).isEqualTo(FileCommitReviewKind.UNREADABLE);
            assertThat(item.detail()).isNotBlank();
        });
        assertThat(Files.readString(state)).isEqualTo("{broken");
    }

    @Test
    void reportsDeferredRecoveryWithoutChangingTheJournalPhase() throws IOException {
        FileCommitManifest deferred = createManifest(FileCommitOwnerType.REMOTE_DOWNLOAD);
        store.create(deferred);
        incidentRegistry.record(deferred.operationId(), "FileSystemException");

        assertThat(service.list()).singleElement().satisfies(item -> {
            assertThat(item.operationId()).isEqualTo(deferred.operationId());
            assertThat(item.kind()).isEqualTo(FileCommitReviewKind.RECOVERY_DEFERRED);
            assertThat(item.detail()).isEqualTo("FileSystemException");
        });
        assertThat(store.load(deferred.operationId()).state().phase()).isEqualTo(FileCommitPhase.PREPARED);
    }

    @Test
    void keepsADeferredIncidentVisibleWhenItsJournalDisappears() throws IOException {
        String operationId = UUID.randomUUID().toString();
        incidentRegistry.record(operationId, "NoSuchFileException");

        assertThat(service.list()).singleElement().satisfies(item -> {
            assertThat(item.operationId()).isEqualTo(operationId);
            assertThat(item.kind()).isEqualTo(FileCommitReviewKind.RECOVERY_DEFERRED);
            assertThat(item.ownerType()).isNull();
            assertThat(item.operationType()).isNull();
        });
    }

    private FileCommitManifest createManifest(FileCommitOwnerType ownerType) throws IOException {
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                new FileCommitOwner(ownerType, UUID.randomUUID().toString()),
                FileCommitOperationType.SINGLE_FILE,
                ConflictPolicy.CANCEL,
                List.of(new FileCommitItem(
                        0,
                        ".endervault/file-staging/" + UUID.randomUUID() + ".tmp",
                        "target.txt",
                        new FileCommitFingerprint(3L, Instant.now(), null),
                        null
                )),
                Instant.now()
        );
        return manifest;
    }

    private FileCommitJournalState state(String operationId, FileCommitPhase phase) {
        return new FileCommitJournalState(operationId, phase, 0, "Test detail", Instant.now());
    }
}
