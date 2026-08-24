package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.ArchiveCommitPlan;
import io.github.fourilla.endervault.storage.ArchiveCommitPlanItem;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageBatchEntry;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommitBatchCoordinatorTest {

    @TempDir
    Path root;

    private StorageService storageService;
    private FileCommitJournalStore journalStore;
    private FileCommitBatchCoordinator batchCoordinator;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(
                properties, new FileActionRegistry(), new TemporaryArtifactRegistry()
        );
        storageService.initialize();
        journalStore = new FileCommitJournalStore(
                new ObjectMapper().findAndRegisterModules(), properties
        );
        journalStore.initialize();
        FileCommitCoordinator singleCoordinator = new FileCommitCoordinator(
                journalStore, storageService, properties
        );
        batchCoordinator = new FileCommitBatchCoordinator(
                journalStore, singleCoordinator, storageService
        );
    }

    @Test
    void commitsEveryPlannedArchiveItemAndKeepsJournalUntilMetadataCompletes() throws Exception {
        ArchiveCommitPlan plan = directPlan();
        FileCommitOwner owner = archiveOwner();

        FileCommitBatchCoordinator.StagedBatchCommit commit = batchCoordinator.commitArchiveExtraction(
                owner, plan, ConflictPolicy.CANCEL, StorageProgressListener.NOOP
        );

        assertThat(commit.result().committedPaths()).containsExactly("a.txt", "docs");
        assertThat(root.resolve("a.txt")).hasContent("a");
        assertThat(root.resolve("docs/guide.txt")).hasContent("guide");
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
        assertThat(batchCoordinator.activeArchiveStagingWorkspaces())
                .containsExactly(plan.workspace().getFileName().toString());

        batchCoordinator.complete(commit.operationId());

        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void resumesWhenFirstAtomicMoveFinishedBeforeItsProgressWasRecorded() throws Exception {
        ArchiveCommitPlan plan = directPlan();
        FileCommitJournalEntry entry = createPreparedJournal(archiveOwner(), plan);
        journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(),
                FileCommitPhase.COMMITTING,
                0,
                "Committing archive batch.",
                Instant.now()
        ));
        ArchiveCommitPlanItem first = plan.items().get(0);
        storageService.commitArchiveStagedEntryNoReplace(first.stagedPath(), first.targetPath());

        FileCommitBatchCoordinator.StagedBatchCommit recovered =
                batchCoordinator.resumeArchiveExtraction(entry.manifest().operationId());

        assertThat(recovered.result().committedPaths()).containsExactly("a.txt", "docs");
        assertThat(root.resolve("a.txt")).hasContent("a");
        assertThat(root.resolve("docs/guide.txt")).hasContent("guide");
        assertThat(journalStore.load(entry.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test
    void resumesAtTheRecordedNextItemWithoutReplayingCommittedEntries() throws Exception {
        ArchiveCommitPlan plan = directPlan();
        FileCommitJournalEntry entry = createPreparedJournal(archiveOwner(), plan);
        ArchiveCommitPlanItem first = plan.items().get(0);
        storageService.commitArchiveStagedEntryNoReplace(first.stagedPath(), first.targetPath());
        journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(),
                FileCommitPhase.COMMITTING,
                1,
                "Committed archive batch item.",
                Instant.now()
        ));

        batchCoordinator.resumeArchiveExtraction(entry.manifest().operationId());

        assertThat(root.resolve("a.txt")).hasContent("a");
        assertThat(root.resolve("docs/guide.txt")).hasContent("guide");
    }

    @Test
    void preservesJournalAndWorkspaceWhenALateTargetCollisionMakesTheBatchAmbiguous() throws Exception {
        ArchiveCommitPlan plan = directPlan();
        FileCommitJournalEntry entry = createPreparedJournal(archiveOwner(), plan);
        journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(),
                FileCommitPhase.COMMITTING,
                0,
                "Committing archive batch.",
                Instant.now()
        ));
        Files.writeString(root.resolve("a.txt"), "late collision");

        assertThatThrownBy(() -> batchCoordinator.resumeArchiveExtraction(entry.manifest().operationId()))
                .isInstanceOf(FileCommitRecoveryRequiredException.class);

        assertThat(root.resolve("a.txt")).hasContent("late collision");
        assertThat(plan.items().get(0).stagedPath()).hasContent("a");
        assertThat(plan.workspace()).exists();
        assertThat(journalStore.load(entry.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.NEEDS_REVIEW);
    }

    @Test
    void refusesToAcceptACommittedDirectoryThatChangedAfterItsMove() throws Exception {
        ArchiveCommitPlan plan = directPlan();
        FileCommitJournalEntry entry = createPreparedJournal(archiveOwner(), plan);
        ArchiveCommitPlanItem first = plan.items().get(0);
        storageService.commitArchiveStagedEntryNoReplace(first.stagedPath(), first.targetPath());
        Files.writeString(root.resolve("a.txt"), "changed");
        journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(),
                FileCommitPhase.COMMITTING,
                0,
                "Committing archive batch.",
                Instant.now()
        ));

        assertThatThrownBy(() -> batchCoordinator.resumeArchiveExtraction(entry.manifest().operationId()))
                .isInstanceOf(FileCommitRecoveryRequiredException.class);

        assertThat(journalStore.load(entry.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.NEEDS_REVIEW);
        assertThat(plan.items().get(1).stagedPath()).exists();
    }

    private ArchiveCommitPlan directPlan() throws Exception {
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("a.txt"), "a");
        Files.createDirectories(content.resolve("docs"));
        Files.writeString(content.resolve("docs/guide.txt"), "guide");
        return storageService.planArchiveCommit(
                content,
                "",
                false,
                "ignored",
                List.of(
                        new StorageBatchEntry("a.txt", false),
                        new StorageBatchEntry("docs", true)
                ),
                ConflictPolicy.CANCEL
        );
    }

    private FileCommitOwner archiveOwner() {
        return new FileCommitOwner(FileCommitOwnerType.ARCHIVE_EXTRACT, UUID.randomUUID().toString());
    }

    private FileCommitJournalEntry createPreparedJournal(
            FileCommitOwner owner,
            ArchiveCommitPlan plan
    ) throws Exception {
        List<FileCommitItem> items = new java.util.ArrayList<>();
        for (int index = 0; index < plan.items().size(); index++) {
            ArchiveCommitPlanItem item = plan.items().get(index);
            items.add(new FileCommitItem(
                    index,
                    storageService.archiveStagingCommitPath(item.stagedPath()),
                    item.targetPath(),
                    FileCommitFingerprints.tree(item.stagedPath(), StorageProgressListener.NOOP),
                    null
            ));
        }
        return journalStore.create(new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                FileCommitOperationType.BATCH,
                ConflictPolicy.CANCEL,
                items,
                Instant.now()
        ));
    }
}
