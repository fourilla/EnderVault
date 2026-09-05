package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.ArchiveCommitPlan;
import io.github.fourilla.endervault.storage.StorageBatchEntry;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommitStartupRecoveryServiceTest {

    @TempDir
    Path root;

    private StorageService storageService;
    private FileCommitJournalStore journalStore;
    private FileCommitCoordinator coordinator;
    private FileCommitBatchCoordinator batchCoordinator;
    private PendingFileDecisionService pendingFileDecisionService;
    private FileCommitRecoveryIncidentRegistry incidentRegistry;
    private FileCommitStartupRecoveryService recoveryService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        TemporaryArtifactRegistry temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(
                properties,
                new io.github.fourilla.endervault.filetool.FileActionRegistry(),
                temporaryArtifactRegistry
        );
        storageService.initialize();
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        journalStore = new FileCommitJournalStore(objectMapper, properties);
        journalStore.initialize();
        coordinator = new FileCommitCoordinator(journalStore, storageService, properties);
        batchCoordinator = new FileCommitBatchCoordinator(journalStore, coordinator, storageService);
        PendingFileDecisionRepository repository = new PendingFileDecisionRepository(objectMapper, properties);
        repository.initialize();
        pendingFileDecisionService = new PendingFileDecisionService(
                repository,
                storageService,
                coordinator,
                temporaryArtifactRegistry,
                List.of()
        );
        incidentRegistry = new FileCommitRecoveryIncidentRegistry();
        recoveryService = new FileCommitStartupRecoveryService(
                coordinator,
                batchCoordinator,
                pendingFileDecisionService,
                storageService,
                incidentRegistry
        );
    }

    @Test
    void resumesRemoteDownloadCommitWithoutRestoringTheMemoryTask() throws Exception {
        Path staged = stagedFile("remote payload");
        createPreparedJournal(
                new FileCommitOwner(FileCommitOwnerType.REMOTE_DOWNLOAD, "remote-task-1"),
                staged,
                "incoming/video.mp4"
        );
        Files.createDirectories(root.resolve("incoming"));

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.recovered()).isEqualTo(1);
        assertThat(summary.pending()).isZero();
        assertThat(root.resolve("incoming/video.mp4")).hasContent("remote payload");
        assertThat(staged).doesNotExist();
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void recoversReadableJournalEvenWhenAnotherJournalIsCorrupt() throws Exception {
        Path staged = stagedFile("recoverable payload");
        FileCommitJournalEntry readable = createPreparedJournal(
                new FileCommitOwner(FileCommitOwnerType.REMOTE_DOWNLOAD, "remote-task-readable"),
                staged,
                "recovered.txt"
        );
        FileCommitJournalEntry corrupt = createPreparedJournal(
                new FileCommitOwner(FileCommitOwnerType.REMOTE_DOWNLOAD, "remote-task-corrupt"),
                stagedFile("corrupt payload"),
                "corrupt.txt"
        );
        Files.writeString(
                root.resolve(".endervault/commit-journal")
                        .resolve(corrupt.manifest().operationId())
                        .resolve("state.json"),
                "{broken"
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.recovered()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(root.resolve("recovered.txt")).hasContent("recoverable payload");
        assertThat(root.resolve(".endervault/commit-journal")
                .resolve(readable.manifest().operationId())).doesNotExist();
        assertThat(root.resolve(".endervault/commit-journal")
                .resolve(corrupt.manifest().operationId())).exists();
        assertThat(incidentRegistry.list())
                .extracting(FileCommitRecoveryIncident::operationId)
                .containsExactly(corrupt.manifest().operationId());
    }

    @Test
    void restoresArchiveCollisionAsPendingDecision() throws Exception {
        Path staged = stagedFile("zip payload");
        Files.writeString(root.resolve("bundle.zip"), "existing archive");
        createPreparedJournal(
                new FileCommitOwner(FileCommitOwnerType.ARCHIVE_CREATE, "archive-task-1"),
                staged,
                "bundle.zip"
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.pending()).isEqualTo(1);
        assertThat(root.resolve("bundle.zip")).hasContent("existing archive");
        assertThat(staged).hasContent("zip payload");
        assertThat(pendingFileDecisionService.list()).singleElement().satisfies(decision -> {
            assertThat(decision.source()).isEqualTo(PendingFileDecisionSource.ARCHIVE_OUTPUT);
            assertThat(decision.sourceReference()).isEqualTo("archive-task-1");
            assertThat(decision.originalFilename()).isEqualTo("bundle.zip");
        });
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void finishesConflictHandoffWithoutCreatingADuplicatePendingDecision() throws Exception {
        FileCommitOwner owner = new FileCommitOwner(FileCommitOwnerType.REMOTE_DOWNLOAD, "remote-task-2");
        Path staged = stagedFile("remote payload");
        Files.writeString(root.resolve("note.txt"), "existing file");
        FileCommitConflictException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> coordinator.commitSingleFile(owner, staged, "", "note.txt"),
                FileCommitConflictException.class
        );
        pendingFileDecisionService.create(
                staged,
                PendingFileDecisionSource.REMOTE_DOWNLOAD,
                "",
                "note.txt",
                Files.size(staged),
                owner.id(),
                null
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.pending()).isEqualTo(1);
        assertThat(pendingFileDecisionService.list()).hasSize(1);
        assertThat(journalStore.list()).isEmpty();
        assertThat(Files.exists(root.resolve(".endervault/commit-journal/" + conflict.operationId())))
                .isFalse();
    }

    @Test
    void leavesAmbiguousMissingFilesForManualReview() throws Exception {
        Path staged = stagedFile("remote payload");
        FileCommitJournalEntry journal = createPreparedJournal(
                new FileCommitOwner(FileCommitOwnerType.REMOTE_DOWNLOAD, "remote-task-3"),
                staged,
                "lost.bin"
        );
        Files.delete(staged);

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(journalStore.load(journal.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.NEEDS_REVIEW);
        assertThat(pendingFileDecisionService.list()).isEmpty();
    }

    @Test
    void finishesPendingReplacementAfterTheFileMoveCompletedBeforeMetadataRemoval() throws Exception {
        Files.writeString(root.resolve("note.txt"), "existing");
        Path staged = stagedFile("replacement");
        PendingFileDecision decision = pendingFileDecisionService.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );
        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                new FileCommitOwner(FileCommitOwnerType.PENDING_FILE_DECISION, decision.id()),
                staged,
                "",
                "note.txt",
                ConflictPolicy.OVERWRITE
        );

        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
        assertThat(pendingFileDecisionService.list()).extracting(PendingFileDecision::id)
                .containsExactly(decision.id());

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.recovered()).isEqualTo(1);
        assertThat(root.resolve("note.txt")).hasContent("replacement");
        assertThat(pendingFileDecisionService.list()).isEmpty();
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void removesJournalWhenPendingMetadataWasRemovedBeforeJournalCompletion() throws Exception {
        Path staged = stagedFile("saved content");
        PendingFileDecision decision = pendingFileDecisionService.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "draft.txt",
                Files.size(staged)
        );
        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                new FileCommitOwner(FileCommitOwnerType.PENDING_FILE_DECISION, decision.id()),
                staged,
                "",
                "saved.txt",
                ConflictPolicy.CANCEL
        );
        pendingFileDecisionService.completeRecoveredCommit(
                decision,
                PendingFileDecisionAction.SAVE_AS,
                commit.file().path()
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.recovered()).isEqualTo(1);
        assertThat(root.resolve("saved.txt")).hasContent("saved content");
        assertThat(pendingFileDecisionService.list()).isEmpty();
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void clearsAbortedPendingCommitJournalAndLeavesDecisionAvailableForRetry() throws Exception {
        Files.writeString(root.resolve("late.txt"), "late collision");
        Path staged = stagedFile("pending content");
        PendingFileDecision decision = pendingFileDecisionService.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "original.txt",
                Files.size(staged)
        );
        org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> coordinator.commitSingleFile(
                        new FileCommitOwner(FileCommitOwnerType.PENDING_FILE_DECISION, decision.id()),
                        staged,
                        "",
                        "late.txt",
                        ConflictPolicy.CANCEL
                ),
                FileCommitConflictException.class
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.pending()).isEqualTo(1);
        assertThat(pendingFileDecisionService.list()).extracting(PendingFileDecision::id)
                .containsExactly(decision.id());
        assertThat(staged).hasContent("pending content");
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void finishesArchiveBatchAndRemovesItsWorkspaceAfterStartup() throws Exception {
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("a.txt"), "a");
        Files.writeString(content.resolve("b.txt"), "b");
        ArchiveCommitPlan plan = storageService.planArchiveCommit(
                content,
                "",
                false,
                "ignored",
                List.of(
                        new StorageBatchEntry("a.txt", false),
                        new StorageBatchEntry("b.txt", false)
                ),
                ConflictPolicy.CANCEL
        );
        FileCommitBatchCoordinator.StagedBatchCommit commit = batchCoordinator.commitArchiveExtraction(
                new FileCommitOwner(FileCommitOwnerType.ARCHIVE_EXTRACT, "extract-task-1"),
                plan,
                ConflictPolicy.CANCEL,
                StorageProgressListener.NOOP
        );

        FileCommitStartupRecoveryService.RecoverySummary summary = recoveryService.recover();

        assertThat(summary.recovered()).isEqualTo(1);
        assertThat(root.resolve("a.txt")).hasContent("a");
        assertThat(root.resolve("b.txt")).hasContent("b");
        assertThat(workspace).doesNotExist();
        assertThat(journalStore.list()).isEmpty();
        assertThat(commit.result().committedCount()).isEqualTo(2);
    }

    private Path stagedFile(String content) throws IOException {
        Path staged = storageService.createFileStagingTemporaryFile("recovery-", ".tmp");
        Files.writeString(staged, content);
        return staged;
    }

    private FileCommitJournalEntry createPreparedJournal(
            FileCommitOwner owner,
            Path staged,
            String targetPath
    ) throws IOException {
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                FileCommitOperationType.SINGLE_FILE,
                ConflictPolicy.CANCEL,
                List.of(new FileCommitItem(
                        0,
                        ".endervault/file-staging/" + staged.getFileName(),
                        targetPath,
                        FileCommitCoordinator.fingerprint(staged),
                        null
                )),
                Instant.now()
        );
        return journalStore.create(manifest);
    }
}
