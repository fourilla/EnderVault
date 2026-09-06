package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommitCoordinatorTest {

    @TempDir
    Path root;

    private FileCommitJournalStore journalStore;
    private FileCommitCoordinator coordinator;
    private StorageService storageService;

    @BeforeEach
    void setUp() throws IOException {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();
        journalStore = new FileCommitJournalStore(
                JsonMapper.builder().findAndAddModules().build(),
                root.resolve(".endervault/commit-journal")
        );
        journalStore.initialize();
        coordinator = new FileCommitCoordinator(journalStore, storageService, properties);
    }

    @Test
    void commitsDirectoryAsSingleUnitAndReplaysTargetOnly() throws Exception {
        Path staged = Files.createDirectory(storageService.resolveFileStagingFile("directory-upload-test"));
        Files.createDirectories(staged.resolve("nested/empty"));
        Files.writeString(staged.resolve("nested/data"), "payload");
        FileCommitOwner owner = new FileCommitOwner(FileCommitOwnerType.DIRECTORY_UPLOAD, "group-1");
        var commit = coordinator.commitSingleDirectory(owner, staged, "", "published");
        var entry = journalStore.load(commit.operationId());
        assertThat(entry.manifest().operationType()).isEqualTo(FileCommitOperationType.SINGLE_DIRECTORY);
        assertThat(entry.manifest().items().getFirst().stagingFingerprint().directory()).isTrue();
        assertThat(coordinator.activeStagingFilenames()).contains("directory-upload-test");
        assertThat(coordinator.resumeSingleFile(commit.operationId()).file().path()).isEqualTo("published");
        assertThat(coordinator.commitSingleDirectory(owner, staged, "", "published")).isEqualTo(commit);
        assertThat(Files.readString(root.resolve("published/nested/data"))).isEqualTo("payload");
        assertThat(root.resolve("published/nested/empty")).isDirectory();
        coordinator.complete(commit.operationId());
        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void resumesDirectoryMovedBeforeJournalAdvanced() throws Exception {
        Path staged = Files.createDirectory(storageService.resolveFileStagingFile("directory-upload-test"));
        Files.writeString(staged.resolve("data"), "payload");
        String id = UUID.randomUUID().toString();
        journalStore.create(new FileCommitManifest(FileCommitManifest.CURRENT_SCHEMA_VERSION, id, owner(),
                FileCommitOperationType.SINGLE_DIRECTORY, ConflictPolicy.CANCEL,
                List.of(new FileCommitItem(0, ".endervault/file-staging/directory-upload-test", "published",
                        FileCommitFingerprints.tree(staged, null), null)), Instant.now()));
        journalStore.updateState(new FileCommitJournalState(id, FileCommitPhase.COMMITTING, 0, null, Instant.now()));
        storageService.commitStagedDirectoryNoReplace(staged, "", "published");
        coordinator.resumeSingleFile(id);
        assertThat(journalStore.load(id).state().phase()).isEqualTo(FileCommitPhase.APPLYING_METADATA);
        assertThat(root.resolve("published/data")).exists();
    }

    @Test
    void directorySourceOnlyRecoveryAndChangedTargetRequireReview() throws Exception {
        Path staged = Files.createDirectory(storageService.resolveFileStagingFile("directory-upload-test"));
        Files.writeString(staged.resolve("data"), "payload");
        String id = UUID.randomUUID().toString();
        journalStore.create(new FileCommitManifest(FileCommitManifest.CURRENT_SCHEMA_VERSION, id, owner(),
                FileCommitOperationType.SINGLE_DIRECTORY, ConflictPolicy.CANCEL,
                List.of(new FileCommitItem(0, ".endervault/file-staging/directory-upload-test", "published",
                        FileCommitFingerprints.tree(staged, null), null)), Instant.now()));
        coordinator.resumeSingleFile(id);
        Files.writeString(root.resolve("published/data"), "changed payload");
        assertThatThrownBy(() -> coordinator.resumeSingleFile(id))
                .isInstanceOf(FileCommitRecoveryRequiredException.class);
        assertThat(journalStore.load(id).state().phase()).isEqualTo(FileCommitPhase.NEEDS_REVIEW);
    }

    @Test
    void directoryCollisionNeverMergesAndOverwriteIsRejected() throws Exception {
        Path staged = Files.createDirectory(storageService.resolveFileStagingFile("directory-upload-test"));
        Files.writeString(staged.resolve("data"), "payload");
        Files.createDirectory(root.resolve("published"));
        assertThatThrownBy(() -> coordinator.commitSingleDirectory(owner(), staged, "", "published",
                ConflictPolicy.OVERWRITE)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        assertThat(journalStore.list()).isEmpty();
        assertThatThrownBy(() -> coordinator.commitSingleDirectory(owner(), staged, "", "published"))
                .isInstanceOf(FileCommitConflictException.class);
        assertThat(staged.resolve("data")).exists();
        assertThat(root.resolve("published")).isEmptyDirectory();
    }

    @Test
    void commitsAStagedFileAndKeepsJournalUntilOwnerMetadataCompletes() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("payload");
        Files.createDirectories(root.resolve("incoming"));

        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                owner, staged, "incoming", "upload.txt"
        );

        assertThat(Files.exists(staged)).isFalse();
        assertThat(Files.readString(root.resolve("incoming/upload.txt"))).isEqualTo("payload");
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);

        coordinator.complete(commit.operationId());

        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void resumesAfterTargetLinkWasCreatedButStagingLinkWasNotRemoved() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("payload");
        Path target = target("incoming/upload.txt");
        createCommittingJournal(owner, staged, "incoming/upload.txt");
        Files.createLink(target, staged);

        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                owner, staged, "incoming", "upload.txt"
        );

        assertThat(Files.exists(staged)).isFalse();
        assertThat(Files.readString(target)).isEqualTo("payload");
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test
    void resumesAfterStagingLinkWasRemovedBeforeJournalAdvanced() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("payload");
        Path target = target("incoming/upload.txt");
        createCommittingJournal(owner, staged, "incoming/upload.txt");
        Files.createLink(target, staged);
        Files.delete(staged);

        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                owner, staged, "incoming", "upload.txt"
        );

        assertThat(Files.readString(target)).isEqualTo("payload");
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test
    void turnsALateDestinationCollisionBackIntoTheNormalConflictFlow() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("new payload");
        Path target = target("incoming/upload.txt");
        createCommittingJournal(owner, staged, "incoming/upload.txt");
        Files.writeString(target, "existing payload");

        FileCommitConflictException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> coordinator.commitSingleFile(owner, staged, "incoming", "upload.txt"),
                FileCommitConflictException.class
        );

        assertThat(Files.readString(staged)).isEqualTo("new payload");
        assertThat(Files.readString(target)).isEqualTo("existing payload");
        assertThat(journalStore.load(conflict.operationId()).state().phase()).isEqualTo(FileCommitPhase.ABORTED);

        coordinator.completeConflict(conflict.operationId());

        assertThat(journalStore.list()).isEmpty();
    }

    @Test
    void journalsAnInitialDestinationCollisionUntilPendingHandoffCompletes() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("new payload");
        Path target = target("incoming/upload.txt");
        Files.writeString(target, "existing payload");

        FileCommitConflictException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> coordinator.commitSingleFile(owner, staged, "incoming", "upload.txt"),
                FileCommitConflictException.class
        );

        assertThat(Files.readString(staged)).isEqualTo("new payload");
        assertThat(Files.readString(target)).isEqualTo("existing payload");
        assertThat(journalStore.load(conflict.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.ABORTED);
    }

    @Test
    void retainsAmbiguousLossForManualInspection() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("payload");
        FileCommitJournalEntry entry = createCommittingJournal(owner, staged, "incoming/upload.txt");
        target("incoming/upload.txt");
        Files.delete(staged);

        assertThatThrownBy(() -> coordinator.commitSingleFile(owner, staged, "incoming", "upload.txt"))
                .isInstanceOf(FileCommitRecoveryRequiredException.class)
                .hasMessageContaining(entry.manifest().operationId());

        assertThat(journalStore.load(entry.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.NEEDS_REVIEW);
    }

    @Test
    void replacesAnUnchangedTargetAndRemovesTheRecoveryBackup() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("replacement");
        Path target = target("incoming/note.txt");
        Files.writeString(target, "existing");

        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                owner,
                staged,
                "incoming",
                "note.txt",
                ConflictPolicy.OVERWRITE
        );

        assertThat(target).hasContent("replacement");
        assertThat(staged).doesNotExist();
        assertThat(replacementBackup(commit.operationId())).doesNotExist();
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test
    void resumesReplacementAfterAtomicMoveBeforeJournalAdvance() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("replacement");
        Path target = target("incoming/note.txt");
        Files.writeString(target, "existing");
        FileCommitJournalEntry journal = createCommittingJournal(
                owner,
                staged,
                "incoming/note.txt",
                ConflictPolicy.OVERWRITE,
                FileCommitCoordinator.fingerprint(target)
        );
        Path backup = replacementBackup(journal.manifest().operationId());
        storageService.createStagedRegularFileReplacementBackup("incoming", "note.txt", backup);
        storageService.commitStagedRegularFileReplace(staged, "incoming", "note.txt");

        FileCommitCoordinator.StagedFileCommit commit = coordinator.commitSingleFile(
                owner,
                staged,
                "incoming",
                "note.txt",
                ConflictPolicy.OVERWRITE
        );

        assertThat(commit.operationId()).isEqualTo(journal.manifest().operationId());
        assertThat(target).hasContent("replacement");
        assertThat(backup).doesNotExist();
        assertThat(journalStore.load(commit.operationId()).state().phase())
                .isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test
    void refusesToReplaceATargetChangedAfterTheJournalWasPrepared() throws IOException {
        FileCommitOwner owner = owner();
        Path staged = stagedFile("replacement");
        Path target = target("incoming/note.txt");
        Files.writeString(target, "existing");
        FileCommitJournalEntry journal = createCommittingJournal(
                owner,
                staged,
                "incoming/note.txt",
                ConflictPolicy.OVERWRITE,
                FileCommitCoordinator.fingerprint(target)
        );
        Files.writeString(target, "changed outside EnderVault");

        assertThatThrownBy(() -> coordinator.commitSingleFile(
                owner,
                staged,
                "incoming",
                "note.txt",
                ConflictPolicy.OVERWRITE
        )).isInstanceOf(FileCommitRecoveryRequiredException.class);

        assertThat(staged).hasContent("replacement");
        assertThat(target).hasContent("changed outside EnderVault");
        assertThat(journalStore.load(journal.manifest().operationId()).state().phase())
                .isEqualTo(FileCommitPhase.NEEDS_REVIEW);
    }

    private Path stagedFile(String content) throws IOException {
        Path staged = storageService.resumableUploadStagingFile(UUID.randomUUID().toString());
        Files.writeString(staged, content);
        return staged;
    }

    private Path target(String relativePath) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        return target;
    }

    private FileCommitJournalEntry createCommittingJournal(
            FileCommitOwner owner,
            Path staged,
            String targetPath
    ) throws IOException {
        return createCommittingJournal(owner, staged, targetPath, ConflictPolicy.CANCEL, null);
    }

    private FileCommitJournalEntry createCommittingJournal(
            FileCommitOwner owner,
            Path staged,
            String targetPath,
            ConflictPolicy conflictPolicy,
            FileCommitFingerprint targetSnapshot
    ) throws IOException {
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                FileCommitOperationType.SINGLE_FILE,
                conflictPolicy,
                List.of(new FileCommitItem(
                        0,
                        ".endervault/file-staging/" + staged.getFileName(),
                        targetPath,
                        FileCommitCoordinator.fingerprint(staged),
                        targetSnapshot
                )),
                Instant.now()
        );
        journalStore.create(manifest);
        return journalStore.updateState(new FileCommitJournalState(
                manifest.operationId(),
                FileCommitPhase.COMMITTING,
                0,
                "Committing staged file.",
                Instant.now()
        ));
    }

    private Path replacementBackup(String operationId) {
        return storageService.resolveFileStagingFile("file-commit-backup-" + operationId + ".tmp");
    }

    private FileCommitOwner owner() {
        return new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, UUID.randomUUID().toString());
    }
}
