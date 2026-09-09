package io.github.fourilla.endervault.directorymerge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryMergeFilePublisherTest {
    @TempDir Path root;
    NasProperties properties;
    StorageService storage;
    DirectoryMergeReviewStore reviews;
    FileCommitJournalStore journals;
    FileCommitCoordinator commits;
    TemporaryArtifactRegistry registry;
    DirectoryMergeFilePublisher publisher;

    @BeforeEach void setup() throws IOException {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = spy(new StorageService(properties));
        storage.initialize();
        reviews = new DirectoryMergeReviewStore(JsonMapper.builder().findAndAddModules().build(), properties);
        journals = new FileCommitJournalStore(JsonMapper.builder().findAndAddModules().build(),
                properties);
        journals.initialize();
        commits = new FileCommitCoordinator(journals, storage, properties);
        registry = new TemporaryArtifactRegistry();
        publisher = new DirectoryMergeFilePublisher(reviews, storage, commits, registry);
        Files.createDirectories(root.resolve("from/photos"));
        Files.createDirectories(root.resolve("to/photos"));
        Files.writeString(root.resolve("from/photos/a.txt"), "new data");
    }

    private DirectoryMergeReview review(Operation operation, DirectoryMergeReview.Choice choice) throws IOException {
        var plan = new DirectoryMergePlanner(storage, properties).planTransfer(operation, "from/photos", "to", null);
        var review = reviews.create(plan);
        return choice == null ? review : reviews.choose(plan.id(), 0, Map.of(item(review).id(), choice));
    }

    private Item item(DirectoryMergeReview review) {
        return review.plan().items().stream().filter(i -> i.relativePath().equals("a.txt")).findFirst().orElseThrow();
    }

    private FileCommitCoordinator.StagedFileCommit publish(DirectoryMergeReview review) throws IOException {
        return publisher.publishTransferFile(review.plan().id(), review.revision(), item(review).id(), null);
    }

    @Test void publishesAdditionButKeepsSourceAndJournalForOwnerMetadata() throws Exception {
        var review = review(Operation.MOVE, null);
        var result = publish(review);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("new data");
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new data");
        assertThat(journals.load(result.operationId()).state().phase()).isEqualTo(FileCommitPhase.APPLYING_METADATA);
        assertThat(publish(review)).isEqualTo(result);
        assertThat(registry.activeArtifacts()).isEmpty();
    }

    @Test void overwritePreservesUnrelatedDestinationFiles() throws Exception {
        Files.writeString(root.resolve("to/photos/a.txt"), "old");
        Files.writeString(root.resolve("to/photos/only-there.txt"), "keep");
        var review = review(Operation.COPY, DirectoryMergeReview.Choice.OVERWRITE);
        publish(review);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("new data");
        assertThat(root.resolve("to/photos/only-there.txt")).hasContent("keep");
    }

    @Test void keepBothReplaysSameNameAfterRestart() throws Exception {
        Files.writeString(root.resolve("to/photos/a.txt"), "old");
        var review = review(Operation.COPY, DirectoryMergeReview.Choice.KEEP_BOTH);
        var result = publish(review);
        assertThat(result.file().path()).isNotEqualTo("to/photos/a.txt");
        reviews = new DirectoryMergeReviewStore(JsonMapper.builder().findAndAddModules().build(), properties);
        commits = new FileCommitCoordinator(journals, storage, properties);
        publisher = new DirectoryMergeFilePublisher(reviews, storage, commits, registry);
        assertThat(publish(review)).isEqualTo(result);
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(2); }
        assertThat(root.resolve("to/photos/a.txt")).hasContent("old");
    }

    @Test void changedApprovalOrMissingChoicesCannotExecute() throws Exception {
        Files.writeString(root.resolve("to/photos/a.txt"), "old");
        var initial = review(Operation.COPY, null);
        assertThatThrownBy(() -> publish(initial)).isInstanceOf(StorageAccessException.class);
        var chosen = reviews.choose(initial.plan().id(), 0, Map.of(item(initial).id(), DirectoryMergeReview.Choice.OVERWRITE));
        assertThatThrownBy(() -> publisher.publishTransferFile(initial.plan().id(), 0, item(initial).id(), null))
                .isInstanceOf(StorageAccessException.class);
        publish(chosen);
        assertThatThrownBy(() -> reviews.choose(chosen.plan().id(), chosen.revision(),
                Map.of(item(chosen).id(), DirectoryMergeReview.Choice.SKIP)))
                .isInstanceOf(StorageAccessException.class).hasMessageContaining("execution has started");
    }

    @Test void newlyCreatedOrChangedTargetDoesNotInheritOverwriteApproval() throws Exception {
        var addition = review(Operation.COPY, null);
        Files.writeString(root.resolve("to/photos/a.txt"), "external");
        assertThatThrownBy(() -> publish(addition)).isInstanceOf(StorageAccessException.class);
        var overwrite = review(Operation.COPY, DirectoryMergeReview.Choice.OVERWRITE);
        Files.writeString(root.resolve("to/photos/a.txt"), "external newer");
        assertThatThrownBy(() -> publish(overwrite)).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("external newer");
        assertThat(journals.list()).isEmpty();
    }

    @Test void changedSourceAndReplacedParentAreRejected() throws Exception {
        var original = review(Operation.COPY, null);
        Files.writeString(root.resolve("from/photos/a.txt"), "different source");
        assertThatThrownBy(() -> publish(original)).isInstanceOf(StorageAccessException.class);
        var fresh = review(Operation.COPY, null);
        Files.move(root.resolve("to/photos"), root.resolve("to/old-photos"));
        Files.createDirectory(root.resolve("to/photos"));
        assertThatThrownBy(() -> publish(fresh)).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
    }

    @Test void mutationDuringCopyAndCancellationRemoveOnlyUnownedStaging() throws Exception {
        var review = review(Operation.COPY, null);
        StorageProgressListener listener = new StorageProgressListener() {
            @Override public void onBytesProcessed(long bytes) {
                assertThat(registry.activeArtifacts()).hasSize(1);
                try { Files.writeString(root.resolve("from/photos/a.txt"), "modified during copy"); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            }
        };
        assertThatThrownBy(() -> publisher.publishTransferFile(review.plan().id(), review.revision(),
                item(review).id(), listener)).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
        assertThat(registry.activeArtifacts()).isEmpty();
        try (var paths = Files.list(root.resolve(".endervault/file-staging"))) { assertThat(paths).isEmpty(); }
        var fresh = review(Operation.COPY, null);
        assertThatThrownBy(() -> publisher.publishTransferFile(fresh.plan().id(), fresh.revision(), item(fresh).id(),
                new StorageProgressListener() {
                    @Override public void onBytesProcessed(long bytes) { throw new IllegalStateException("cancelled"); }
                })).isInstanceOf(IllegalStateException.class).hasMessage("cancelled");
        assertThat(root.resolve("from/photos/a.txt")).hasContent("modified during copy");
        assertThat(journals.list()).isEmpty();
    }

    @Test void resumesAfterFilesystemCommitBeforeJournalAdvance() throws Exception {
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (!once.getAndSet(true)) throw new IOException("simulated interruption after publish");
            return result;
        }).when(storage).commitStagedRegularFileNoReplace(any(Path.class), anyString(), anyString());
        var review = review(Operation.MOVE, null);
        assertThatThrownBy(() -> publish(review)).isInstanceOf(IOException.class);
        assertThat(journals.list()).hasSize(1);
        assertThat(journals.list().getFirst().state().phase()).isEqualTo(FileCommitPhase.COMMITTING);
        publisher = new DirectoryMergeFilePublisher(reviews, storage,
                new FileCommitCoordinator(journals, storage, properties), registry);
        var result = publish(review);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("new data");
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new data");
        assertThat(journals.load(result.operationId()).state().phase()).isEqualTo(FileCommitPhase.APPLYING_METADATA);
    }

    @Test void preparedJournalCannotResumeIntoReplacedParentOrFromChangedSource() throws Exception {
        FileCommitCoordinator interrupted = spy(commits);
        doThrow(new IOException("interrupted before publish")).when(interrupted).resumeSingleFile(anyString());
        publisher = new DirectoryMergeFilePublisher(reviews, storage, interrupted, registry);
        var review = review(Operation.COPY, null);
        assertThatThrownBy(() -> publish(review)).isInstanceOf(IOException.class);
        assertThat(journals.list().getFirst().state().phase()).isEqualTo(FileCommitPhase.PREPARED);
        var stagedNames = commits.activeStagingFilenames();
        assertThat(stagedNames).hasSize(1);
        Files.move(root.resolve("to/photos"), root.resolve("to/old-photos"));
        Files.createDirectory(root.resolve("to/photos"));
        publisher = new DirectoryMergeFilePublisher(reviews, storage, commits, registry);
        assertThatThrownBy(() -> publish(review)).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("parent changed");
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
        Files.delete(root.resolve("to/photos"));
        Files.move(root.resolve("to/old-photos"), root.resolve("to/photos"));
        Files.writeString(root.resolve("from/photos/a.txt"), "new external source");
        assertThatThrownBy(() -> publish(review)).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("source changed");
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
        assertThat(commits.activeStagingFilenames()).isEqualTo(stagedNames);
        assertThat(storage.resolveFileStagingFile(stagedNames.iterator().next())).hasContent("new data");
    }

    @Test void reviewedJournalDoesNotRecaptureChangedTargetAndRechecksOnResume() throws Exception {
        Path target = Files.writeString(root.resolve("to/photos/a.txt"), "old");
        var snapshot = FileCommitFingerprints.regularFile(target);
        Path staged = storage.createFileStagingTemporaryFile("test-", ".tmp");
        Files.writeString(staged, "new");
        var owner = new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE, "test");
        Files.writeString(target, "changed before prepare");
        assertThatThrownBy(() -> commits.prepareReviewedSingleFile(owner, staged, "to/photos", "a.txt",
                ConflictPolicy.OVERWRITE, snapshot)).isInstanceOf(StorageAccessException.class);
        assertThat(journals.list()).isEmpty();
        var currentSnapshot = FileCommitFingerprints.regularFile(target);
        String operation = commits.prepareReviewedSingleFile(owner, staged, "to/photos", "a.txt",
                ConflictPolicy.OVERWRITE, currentSnapshot);
        assertThat(journals.load(operation).state().phase()).isEqualTo(FileCommitPhase.PREPARED);
        Files.writeString(target, "changed after prepare");
        assertThatThrownBy(() -> commits.resumeSingleFile(operation)).isInstanceOf(FileCommitRecoveryRequiredException.class);
        assertThat(target).hasContent("changed after prepare");
        assertThat(staged).hasContent("new");
        assertThat(commits.activeStagingFilenames()).contains(staged.getFileName().toString());
    }

    @Test void pendingFileUsesSamePublisherWithoutDeletingUploadedSource() throws Exception {
        Path source = Files.createDirectory(storage.resolveFileStagingFile("pending-directory"));
        Files.writeString(source.resolve("a.txt"), "uploaded data");
        var pending = new PendingFileDecision(UUID.randomUUID().toString(), PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "pending-directory", "to", "photos", 13, Instant.now(), null, "group", "admin", true);
        var plan = new DirectoryMergePlanner(storage, properties).planPending(pending, null);
        var review = reviews.create(plan);
        assertThatThrownBy(() -> publish(review)).isInstanceOf(StorageAccessException.class);
        var result = publisher.publishPendingFile(plan.id(), 0, item(review).id(), pending, null);
        assertThat(root.resolve(result.file().path())).hasContent("uploaded data");
        assertThat(source.resolve("a.txt")).hasContent("uploaded data");
        assertThat(publisher.publishPendingFile(plan.id(), 0, item(review).id(), pending, null)).isEqualTo(result);
    }

    @Test void fileAgainstDirectoryCanKeepBothButNeverReplaceItsTree() throws Exception {
        Files.createDirectory(root.resolve("to/photos/a.txt"));
        Files.writeString(root.resolve("to/photos/a.txt/only.txt"), "keep this tree");
        var review = review(Operation.COPY, DirectoryMergeReview.Choice.KEEP_BOTH);
        var result = publish(review);
        assertThat(root.resolve(result.file().path())).hasContent("new data");
        assertThat(root.resolve("to/photos/a.txt/only.txt")).hasContent("keep this tree");
    }

    @Test void skippedItemsAndUnpreparedDirectoryParentsDoNotPublishAnything() throws Exception {
        Files.writeString(root.resolve("to/photos/a.txt"), "old");
        var skip = review(Operation.MOVE, DirectoryMergeReview.Choice.SKIP);
        assertThatThrownBy(() -> publish(skip)).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new data");
        Files.delete(root.resolve("to/photos/a.txt"));
        Files.delete(root.resolve("to/photos"));
        var missingParent = review(Operation.COPY, null);
        assertThatThrownBy(() -> publish(missingParent)).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("parent is not prepared");
        assertThat(root.resolve("to/photos")).doesNotExist();
        assertThat(journals.list()).isEmpty();
    }
}
