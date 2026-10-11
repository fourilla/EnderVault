package io.github.fourilla.endervault.directorytransfer;

import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Uses real review files and trees; only the OS primitive and lifecycle fault points are substituted. */
class DirectoryTransferWholeMoveTest {
    @TempDir Path root;
    NasProperties properties;
    ObjectMapper mapper;
    StorageService storage;
    DirectoryTransferReviewStore reviews;
    FileCommitJournalStore journals;
    FileCommitCoordinator commits;
    DirectoryTransferExecution execution;
    DirectoryTransferFinalizer finalizer;
    FileSystemOperations fileSystem;
    FileLifecycleService lifecycle;
    DirectoryTransferWholeMoveCoordinator wholeMoves;
    DirectoryTransferService transfers;
    DirectoryTransferReview review;

    @BeforeEach void setup() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
        mapper = JsonMapper.builder().findAndAddModules().build();
        reviews = spy(new DirectoryTransferReviewStore(mapper, properties));
        journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        commits = spy(new FileCommitCoordinator(journals, storage, properties));
        lifecycle = mock(FileLifecycleService.class);
        fileSystem = mock(FileSystemOperations.class);
        when(fileSystem.supportsNoReplaceMove(any(), any())).thenReturn(true);
        when(fileSystem.readIdentity(any())).thenAnswer(call -> FileSystemOperations.systemDefault().readIdentity(call.getArgument(0, Path.class)));
        when(fileSystem.moveNoReplace(any(), any())).thenAnswer(call -> {
            Files.move(call.getArgument(0, Path.class), call.getArgument(1, Path.class));
            return FileSystemOperations.MoveResult.MOVED;
        });
        rebuildWorkers();
        file("from/photos/sub/a.txt", "a");
        Files.createDirectories(root.resolve("to"));
    }

    void rebuildWorkers() {
        var registry = new TemporaryArtifactRegistry();
        execution = spy(new DirectoryTransferExecution(reviews,
                new DirectoryTransferFilePublisher(reviews, storage, commits, registry), commits, storage, registry));
        finalizer = spy(new DirectoryTransferFinalizer(reviews, storage, commits, lifecycle, mapper));
        wholeMoves = new DirectoryTransferWholeMoveCoordinator(reviews, storage, fileSystem, lifecycle);
        transfers = new DirectoryTransferService(reviews, execution, finalizer, wholeMoves);
    }

    void restart() {
        reviews = spy(new DirectoryTransferReviewStore(mapper, properties));
        rebuildWorkers();
    }

    Path file(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    DirectoryTransferReview plan(Operation operation) throws IOException {
        review = reviews.create(new DirectoryTransferPlanner(storage, properties)
                .planTransfer(operation, "from/photos", "to", null));
        return review;
    }

    DirectoryTransferRun execute() throws IOException {
        return transfers.execute(review.plan().id(), review.revision(), null);
    }

    DirectoryTransferStartupRecoveryService recovery() {
        return new DirectoryTransferStartupRecoveryService(reviews, transfers, null);
    }

    void assertWholeMoveComplete() throws IOException {
        String id = review.plan().id();
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.run(id).strategy()).isEqualTo(DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE);
        assertThat(reviews.move(id).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.COMPLETE);
        assertThat(reviews.move(id).entryKeys()).hasSize(review.plan().items().size());
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        assertThat(reviews.results(review).values()).hasSize(review.plan().items().size())
                .allSatisfy(result -> {
                    assertThat(result.status()).isEqualTo(DirectoryTransferResult.Status.MOVED);
                    assertThat(result.renameId()).isEqualTo(id);
                    assertThat(result.commitId()).isNull();
                });
        for (var item : review.plan().items()) {
            assertThat(reviews.completion(id, item.id()).phase()).isEqualTo(DirectoryTransferCompletion.Phase.COMPLETE);
            assertThat(FileSystemOperations.systemDefault().readIdentity(root.resolve("to/photos").resolve(item.relativePath())))
                    .isNotBlank().isEqualTo(reviews.move(id).entryKeys().get(item.relativePath()));
        }
        assertThat(journals.list()).isEmpty();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verify(finalizer, never()).completeTransfer(anyString(), anyLong(), any());
    }

    @Test void freshMovePreservesNestedEmptyHiddenTreeAndFileIdentitiesWithoutCopying() throws Exception {
        Files.createDirectories(root.resolve("from/photos/empty"));
        file("from/photos/.hidden", "hidden");
        file("from/photos/sub/한글 + & [1], %;.txt", "special");
        plan(Operation.MOVE);
        var before = new HashMap<String, Snapshot>();
        var beforeKeys = new HashMap<String, String>();
        for (var item : review.plan().items()) {
            before.put(item.relativePath(), item.source());
            beforeKeys.put(item.relativePath(), FileSystemOperations.systemDefault().readIdentity(root.resolve("from/photos").resolve(item.relativePath())));
        }
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        assertThat(reviews.move(review.plan().id()).entryKeys()).isEqualTo(beforeKeys);
        for (var entry : before.entrySet()) {
            assertThat(DirectoryTransferPlanner.snapshot(root.resolve("to/photos").resolve(entry.getKey())))
                    .isEqualTo(entry.getValue());
        }
        assertThat(root.resolve("to/photos/empty")).isEmptyDirectory();
        assertThat(root.resolve("to/photos/.hidden")).hasContent("hidden");
        verify(fileSystem, times(1)).moveNoReplace(root.resolve("from/photos"), root.resolve("to/photos"));
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
        verifyNoMoreInteractions(lifecycle);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
    }

    @Test void actualSupportedLocalFileSystemMovesThroughTheCoordinatorWithoutCopying() throws Exception {
        var actualNativeMove = FileSystemOperations.systemDefault();
        Assumptions.assumeTrue(actualNativeMove.supportsNoReplaceMove(root.resolve("from/photos"), root.resolve("to")),
                "Whole-directory move requires a supported local file system and native no-replace primitive.");
        file("from/photos/sub/원본 % & +.txt", "native unicode move");
        fileSystem = spy(actualNativeMove);
        rebuildWorkers();
        plan(Operation.MOVE);
        var beforeKeys = new HashMap<String, String>();
        for (var item : review.plan().items()) {
            beforeKeys.put(item.relativePath(), FileSystemOperations.systemDefault().readIdentity(root.resolve("from/photos").resolve(item.relativePath())));
        }
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        assertThat(reviews.move(review.plan().id()).entryKeys()).isEqualTo(beforeKeys);
        for (var item : review.plan().items()) {
            assertThat(DirectoryTransferPlanner.snapshot(root.resolve("to/photos").resolve(item.relativePath())))
                    .isEqualTo(item.source());
        }
        assertThat(root.resolve("to/photos/sub/원본 % & +.txt")).hasContent("native unicode move");
        verify(fileSystem, times(1)).moveNoReplace(root.resolve("from/photos"), root.resolve("to/photos"));
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void directoryFinalizingProgressDoesNotDisableCancellationForTheWholeBatch() throws Exception {
        plan(Operation.MOVE);
        var taskFinalizingCalls = new AtomicInteger();
        var directoryFinalizingSeen = new AtomicBoolean();
        var listener = new StorageProgressListener() {
            @Override public void onFinalizing() { taskFinalizingCalls.incrementAndGet(); }
            @Override public void onProgress(String message, long completedItems, long totalItems,
                    long completedBytes, long totalBytes) {
                if ("Finalizing".equals(message)) directoryFinalizingSeen.set(true);
            }
        };
        assertThat(transfers.execute(review.plan().id(), review.revision(), listener).phase())
                .isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        assertThat(directoryFinalizingSeen).isTrue();
        assertThat(taskFinalizingCalls).hasValue(0);
    }

    @Test void absentNativeSupportKeepsTheItemwisePipeline() throws Exception {
        when(fileSystem.supportsNoReplaceMove(any(), any())).thenReturn(false);
        plan(Operation.MOVE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.run(review.plan().id()).strategy()).isEqualTo(DirectoryTransferRun.Strategy.ITEMWISE);
        assertThat(reviews.move(review.plan().id())).isNull();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
    }

    @Test void unavailableFileIdentityKeepsFreshMovesOnTheItemwisePipeline() throws Exception {
        doReturn(null).when(fileSystem).readIdentity(any());
        plan(Operation.MOVE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.run(review.plan().id()).strategy()).isEqualTo(DirectoryTransferRun.Strategy.ITEMWISE);
        assertThat(reviews.move(review.plan().id())).isNull();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
    }

    @Test void previouslySelectedMoveFallsBackWhenThePrimitiveDisappearsButItsIdentityProofRemains() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("restart before move")).when(fileSystem).moveNoReplace(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.INTENT);
        when(fileSystem.supportsNoReplaceMove(any(), any())).thenReturn(false);
        doReturn(FileSystemOperations.MoveResult.UNSUPPORTED).when(fileSystem).moveNoReplace(any(), any());
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.FALLBACK);
        assertThat(reviews.run(review.plan().id()).strategy()).isEqualTo(DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE);
        verify(execution).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
    }

    @Test void losingIdentitySupportBeforeRenameStopsRecoveryInsteadOfCopyingUnprovenFiles() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("restart before move")).when(fileSystem).moveNoReplace(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        doReturn(null).when(fileSystem).readIdentity(any());
        when(fileSystem.supportsNoReplaceMove(any(), any())).thenReturn(false);
        restart();
        assertThat(recovery().recover().review()).isEqualTo(1);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void losingIdentitySupportAfterRenameKeepsTheMovedTreeAndStopsMetadataRecovery() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            Object value = call.callRealMethod();
            if (next.phase() == DirectoryTransferMoveRecord.Phase.RENAMED) throw new IOException("restart after move");
            return value;
        }).when(reviews).saveMove(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        doReturn(null).when(fileSystem).readIdentity(any());
        when(fileSystem.supportsNoReplaceMove(any(), any())).thenReturn(false);
        restart();
        assertThat(recovery().recover().review()).isEqualTo(1);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void verifiedUnsupportedPrimitiveRecordsFallbackBeforePublishing() throws Exception {
        doReturn(FileSystemOperations.MoveResult.UNSUPPORTED).when(fileSystem).moveNoReplace(any(), any());
        plan(Operation.MOVE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.FALLBACK);
        verify(execution).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
    }

    @Test void copyAndAnExistingRootDirectoryUseTheOriginalPipeline() throws Exception {
        plan(Operation.COPY);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        verify(fileSystem, never()).moveNoReplace(any(), any());
        plan(Operation.MOVE);
        review = reviews.chooseAll(review.plan().id(), 0, DirectoryTransferReview.Choice.OVERWRITE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, times(2)).publishTransfer(anyString(), anyLong(), any());
    }

    @Test void anExistingEmptyTargetRootStillUsesDirectoryMerge() throws Exception {
        Files.createDirectories(root.resolve("to/photos"));
        plan(Operation.MOVE);
        assertThat(review.choices()).isEmpty();
        assertThat(review.plan().items()).filteredOn(item -> item.relativePath().isEmpty())
                .singleElement().extracting(Item::conflict).isEqualTo(Conflict.MERGE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
    }

    @Test void legacyPausedPublishingRunNeverSelectsTheNewStrategy() throws Exception {
        plan(Operation.MOVE);
        reviews.freeze(review.plan().id(), 0);
        reviews.saveRun(null, new DirectoryTransferRun(review.plan().id(), 0,
                DirectoryTransferRun.Phase.PUBLISHING, true));
        assertThat(recovery().recover().recovered()).isZero();
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.run(review.plan().id()).strategy()).isEqualTo(DirectoryTransferRun.Strategy.ITEMWISE);
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution).publishTransfer(anyString(), anyLong(), any());
    }

    @Test void changedSourceAndAddedUnreviewedDescendantsCannotBeMoved() throws Exception {
        plan(Operation.MOVE);
        file("from/photos/unreviewed.txt", "must stay");
        assertThatThrownBy(this::execute).isInstanceOfAny(IOException.class, StorageAccessException.class);
        assertThat(root.resolve("from/photos/unreviewed.txt")).hasContent("must stay");
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void changedReviewedFileCannotFallBackToPublishingOtherItems() throws Exception {
        plan(Operation.MOVE);
        file("from/photos/sub/a.txt", "changed after review");
        assertThatThrownBy(this::execute).isInstanceOfAny(IOException.class, StorageAccessException.class);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("changed after review");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void targetCreatedBetweenInspectionAndRenameIsPreserved() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            file("to/photos/other.txt", "new destination");
            throw new java.nio.file.FileAlreadyExistsException(call.getArgument(1, Path.class).toString());
        }).when(fileSystem).moveNoReplace(any(), any());
        try { execute(); } catch (IOException | StorageAccessException ignored) { }
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/other.txt")).hasContent("new destination");
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
        assertThat(reviews.move(review.plan().id()).phase()).isNotEqualTo(DirectoryTransferMoveRecord.Phase.COMPLETE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "to"})
    void missingParentAfterInitialWholeRunSaveSettlesForReviewWithoutAutomaticRetry(String parent) throws Exception {
        plan(Operation.MOVE);
        var movedParent = new AtomicBoolean();
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferRun.class);
            Object value = call.callRealMethod();
            if (next.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE
                    && !movedParent.getAndSet(true)) {
                Files.move(root.resolve(parent), root.resolve(parent + "-moved"));
            }
            return value;
        }).when(reviews).saveRun(any(), any());
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(reviews.move(review.plan().id())).isNull();
        Path preservedOriginal = root.resolve(parent.equals("from")
                ? "from-moved/photos/sub/a.txt" : "from/photos/sub/a.txt");
        assertThat(preservedOriginal).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        assertThat(root.resolve(parent + "-moved")).isDirectory();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        restart();
        var summary = recovery().recover();
        assertThat(summary.recovered()).isZero();
        assertThat(summary.deferred()).isZero();
        assertThat(preservedOriginal).hasContent("a");
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void changedSourceAfterInitialWholeRunSaveRecordsEmptyIdentityIntentAndSettlesForReview() throws Exception {
        plan(Operation.MOVE);
        var changed = new AtomicBoolean();
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferRun.class);
            Object value = call.callRealMethod();
            if (next.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE
                    && !changed.getAndSet(true)) {
                file("from/photos/sub/a.txt", "changed after durable whole run");
                file("from/photos/unreviewed.txt", "unreviewed addition");
            }
            return value;
        }).when(reviews).saveRun(any(), any());
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        var evidence = reviews.move(review.plan().id());
        assertThat(evidence.phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW);
        assertThat(evidence.entryKeys()).isEmpty();
        verify(reviews).saveMove(isNull(), argThat(move -> move.phase() == DirectoryTransferMoveRecord.Phase.INTENT
                && move.entryKeys().isEmpty()));
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("changed after durable whole run");
        assertThat(root.resolve("from/photos/unreviewed.txt")).hasContent("unreviewed addition");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        restart();
        assertThat(recovery().recover().recovered()).isZero();
        assertThat(reviews.move(review.plan().id())).isEqualTo(evidence);
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void linuxParentCreationTimeFallbackDoesNotInvalidateAnUnchangedParentKey() throws Exception {
        plan(Operation.MOVE);
        var keys = new HashMap<String, String>();
        for (var item : review.plan().items()) {
            keys.put(item.relativePath(), FileSystemOperations.systemDefault().readIdentity(root.resolve("from/photos").resolve(item.relativePath())));
        }
        // Some Linux providers expose mtime as creationTime. Renaming a child changes those parent times,
        // but the parent's stable filesystem key still proves that it is the approved directory.
        var sourceParent = new Snapshot(Kind.DIRECTORY, 0, Instant.EPOCH,
                FileSystemOperations.systemDefault().readIdentity(root.resolve("from")), Instant.EPOCH);
        var targetParent = new Snapshot(Kind.DIRECTORY, 0, Instant.EPOCH,
                FileSystemOperations.systemDefault().readIdentity(root.resolve("to")), Instant.EPOCH);
        reviews.freeze(review.plan().id(), review.revision());
        reviews.saveRun(null, new DirectoryTransferRun(review.plan().id(), review.revision(),
                DirectoryTransferRun.Phase.PUBLISHING, false, DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE));
        reviews.saveMove(null, new DirectoryTransferMoveRecord(review.plan().id(), review.revision(),
                review.plan().sourceReference(), review.plan().destinationPath(), sourceParent, targetParent,
                DirectoryTransferMoveRecord.fingerprint(review.plan()), DirectoryTransferMoveRecord.Phase.INTENT, null, keys));
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        assertThat(reviews.move(review.plan().id()).sourceParent()).isEqualTo(sourceParent);
        assertThat(reviews.move(review.plan().id()).targetParent()).isEqualTo(targetParent);
        verify(fileSystem, times(1)).moveNoReplace(root.resolve("from/photos"), root.resolve("to/photos"));
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void sourceMutationFromMovingProgressIsDetectedBeforeCallingNativeMove() throws Exception {
        plan(Operation.MOVE);
        var listener = new StorageProgressListener() {
            @Override public void onProgress(String phase, long completed, long total, long currentBytes, long currentTotal) {
                if ("Moving".equals(phase)) {
                    try { file("from/photos/sub/a.txt", "changed at moving notification"); }
                    catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                }
            }
        };
        assertThat(transfers.execute(review.plan().id(), review.revision(), listener).phase())
                .isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("changed at moving notification");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void cancellationRequestedFromMovingProgressPausesTheDurableIntentBeforeNativeMove() throws Exception {
        plan(Operation.MOVE);
        var canceled = new AtomicBoolean();
        var listener = new StorageProgressListener() {
            @Override public void checkCanceled() { if (canceled.get()) throw new TaskCanceledException(); }
            @Override public void onProgress(String phase, long completed, long total, long currentBytes, long currentTotal) {
                if ("Moving".equals(phase)) canceled.set(true);
            }
        };
        assertThatThrownBy(() -> transfers.execute(review.plan().id(), review.revision(), listener))
                .isInstanceOf(TaskCanceledException.class);
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.INTENT);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        restart();
        assertThat(recovery().recover().recovered()).isZero();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
    }

    @Test void completedWholeMoveCannotEnterTheDirectItemwisePublicationOrFinalizationRoutes() throws Exception {
        plan(Operation.MOVE);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        var evidence = reviews.move(review.plan().id());
        var results = reviews.results(review);
        var before = new HashMap<String, Snapshot>();
        for (var item : review.plan().items()) {
            before.put(item.relativePath(), DirectoryTransferPlanner.snapshot(root.resolve("to/photos").resolve(item.relativePath())));
        }
        assertThatThrownBy(() -> execution.publishTransfer(review.plan().id(), review.revision(), null))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> finalizer.completeTransfer(review.plan().id(), review.revision(), null))
                .isInstanceOf(StorageAccessException.class);
        assertThat(reviews.move(review.plan().id())).isEqualTo(evidence);
        assertThat(reviews.results(review)).isEqualTo(results);
        assertThat(root.resolve("from/photos")).doesNotExist();
        for (var entry : before.entrySet()) {
            assertThat(DirectoryTransferPlanner.snapshot(root.resolve("to/photos").resolve(entry.getKey())))
                    .isEqualTo(entry.getValue());
        }
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void targetParentReplacementAfterIntentCannotRedirectTheWholeMove() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            Object value = call.callRealMethod();
            if (next.phase() == DirectoryTransferMoveRecord.Phase.INTENT) {
                Files.move(root.resolve("to"), root.resolve("original-to"));
                Files.createDirectory(root.resolve("to"));
            }
            return value;
        }).when(reviews).saveMove(any(), any());
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void sourceParentReplacementAfterIntentCannotMoveAnUnreviewedReplacement() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            Object value = call.callRealMethod();
            if (next.phase() == DirectoryTransferMoveRecord.Phase.INTENT) {
                Files.move(root.resolve("from"), root.resolve("original-from"));
                file("from/photos/sub/a.txt", "replacement tree");
            }
            return value;
        }).when(reviews).saveMove(any(), any());
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("original-from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("replacement tree");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void crashAfterNativeRenameBeforeRenamedRecordRecoversWithoutRenamingAgain() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            if (next.phase() == DirectoryTransferMoveRecord.Phase.RENAMED) throw new IOException("record unavailable");
            return call.callRealMethod();
        }).when(reviews).saveMove(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void crashAfterDurableRenamedRecordRecoversMetadataOnly() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            Object value = call.callRealMethod();
            if (next.phase() == DirectoryTransferMoveRecord.Phase.RENAMED) throw new IOException("after durable rename record");
            return value;
        }).when(reviews).saveMove(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.RENAMED);
        verifyNoInteractions(lifecycle);
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void transientMetadataFailureReplaysRootMetadataWithoutCopyingOrDeleting() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("metadata unavailable")).doNothing().when(lifecycle)
                .applyMovedPathMetadata("from/photos", "to/photos");
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(lifecycle, times(2)).applyMovedPathMetadata("from/photos", "to/photos");
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
    }

    @Test void crashWhileRecordingMovedItemsDoesNotReplayCompletedRootMetadata() throws Exception {
        plan(Operation.MOVE);
        var first = new AtomicBoolean();
        doAnswer(call -> {
            var value = call.callRealMethod();
            if (!first.getAndSet(true)) throw new IOException("after first moved result");
            return value;
        }).when(reviews).recordResult(any(), any(), any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.METADATA_APPLIED);
        assertThat(reviews.results(review)).hasSize(1);
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void crashAfterMetadataAppliedRecordAvoidsDuplicateRootMetadata() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferMoveRecord.class);
            Object value = call.callRealMethod();
            if (next.phase() == DirectoryTransferMoveRecord.Phase.METADATA_APPLIED) throw new IOException("after durable metadata record");
            return value;
        }).when(reviews).saveMove(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.METADATA_APPLIED);
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void completedMoveEvidenceCanSettleARunAfterTheRunWriteFails() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            var next = call.getArgument(1, DirectoryTransferRun.class);
            if (next.phase() == DirectoryTransferRun.Phase.COMPLETE) throw new IOException("run completion unavailable");
            return call.callRealMethod();
        }).when(reviews).saveRun(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.move(review.plan().id()).phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.COMPLETE);
        restart();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void sourceRecreationAfterRenameBlocksMetadataReplayAndKeepsBothTrees() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("metadata unavailable")).when(lifecycle)
                .applyMovedPathMetadata("from/photos", "to/photos");
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        file("from/photos/new.txt", "new original");
        restart();
        var summary = recovery().recover();
        assertThat(summary.recovered()).isZero();
        assertThat(summary.review()).isEqualTo(1);
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/new.txt")).hasContent("new original");
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        var query = new DirectoryTransferQueryService(reviews).get(review.plan().id(), 0, 50, false).review();
        assertThat(query.canReplan()).isFalse();
        var evidence = reviews.move(review.plan().id());
        assertThatThrownBy(() -> new DirectoryTransferReplanningService(reviews,
                new DirectoryTransferPlanner(storage, properties), storage).replan(review.plan().id(), 0, null))
                .isInstanceOf(StorageAccessException.class);
        assertThat(reviews.move(review.plan().id())).isEqualTo(evidence);
        assertThat(reviews.successor(review.plan().id())).isNull();
        verify(lifecycle, times(1)).applyMovedPathMetadata(anyString(), anyString());
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
    }

    @Test void changedDestinationAfterRenameBlocksMetadataReplay() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("metadata unavailable")).when(lifecycle)
                .applyMovedPathMetadata("from/photos", "to/photos");
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        file("to/photos/sub/a.txt", "external modification");
        restart();
        assertThat(recovery().recover().review()).isEqualTo(1);
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("external modification");
        verify(lifecycle, times(1)).applyMovedPathMetadata(anyString(), anyString());
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
    }

    @Test void unknownNativeIoFailureNeverBlindlyFallsBackToCopy() throws Exception {
        plan(Operation.MOVE);
        doThrow(new IOException("uncertain native outcome")).when(fileSystem).moveNoReplace(any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        verifyNoInteractions(lifecycle);
        assertThat(reviews.move(review.plan().id()).phase()).isNotEqualTo(DirectoryTransferMoveRecord.Phase.FALLBACK);
    }

    @Test void falseUnsupportedResultCannotHideAnAlreadyMovedTree() throws Exception {
        plan(Operation.MOVE);
        doAnswer(call -> {
            Files.move(call.getArgument(0, Path.class), call.getArgument(1, Path.class));
            return FileSystemOperations.MoveResult.UNSUPPORTED;
        }).when(fileSystem).moveNoReplace(any(), any());
        try { execute(); } catch (IOException | StorageAccessException ignored) { }
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        verify(execution, never()).publishTransfer(anyString(), anyLong(), any());
        assertThat(reviews.move(review.plan().id()).phase()).isNotEqualTo(DirectoryTransferMoveRecord.Phase.FALLBACK);
    }

    @Test void cancellationBeforeRenamePausesAndStartupDoesNotMoveAnything() throws Exception {
        plan(Operation.MOVE);
        var cancel = new StorageProgressListener() {
            @Override public void checkCanceled() { throw new TaskCanceledException(); }
        };
        assertThatThrownBy(() -> transfers.execute(review.plan().id(), 0, cancel))
                .isInstanceOf(TaskCanceledException.class);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).doesNotExist();
        assertThat(recovery().recover().recovered()).isZero();
        verify(fileSystem, never()).moveNoReplace(any(), any());
        verifyNoInteractions(lifecycle);
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
    }

    @Test void cancellationAfterRenamePausesMetadataUntilExplicitResume() throws Exception {
        plan(Operation.MOVE);
        var renamed = new AtomicBoolean();
        doAnswer(call -> {
            Files.move(call.getArgument(0, Path.class), call.getArgument(1, Path.class));
            renamed.set(true);
            return FileSystemOperations.MoveResult.MOVED;
        }).when(fileSystem).moveNoReplace(any(), any());
        var listener = new StorageProgressListener() {
            @Override public void checkCanceled() { if (renamed.get()) throw new TaskCanceledException(); }
        };
        assertThatThrownBy(() -> transfers.execute(review.plan().id(), 0, listener))
                .isInstanceOf(TaskCanceledException.class);
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
        var evidence = reviews.move(review.plan().id());
        var query = new DirectoryTransferQueryService(reviews).get(review.plan().id(), 0, 50, false).review();
        assertThat(query.canAbandonRemainingTransfer()).isFalse();
        assertThatThrownBy(() -> transfers.abandonRemainingTransfer(review.plan().id(), 0))
                .isInstanceOf(StorageAccessException.class);
        assertThat(reviews.move(review.plan().id())).isEqualTo(evidence);
        assertThat(root.resolve("to/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        restart();
        assertThat(recovery().recover().recovered()).isZero();
        assertThat(execute().phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertWholeMoveComplete();
        verify(fileSystem, times(1)).moveNoReplace(any(), any());
        verify(lifecycle).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void pausedFallbackKeepsItsApprovalAndCannotAbandonThroughTheItemwiseCleanup() throws Exception {
        file("from/photos/sub/b.txt", "b");
        plan(Operation.MOVE);
        doReturn(FileSystemOperations.MoveResult.UNSUPPORTED).when(fileSystem).moveNoReplace(any(), any());
        var published = new AtomicBoolean();
        doAnswer(call -> {
            Object value = call.callRealMethod();
            if (call.getArgument(1, Item.class).source().kind() == Kind.FILE) published.set(true);
            return value;
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> transfers.execute(review.plan().id(), 0, new StorageProgressListener() {
            @Override public void checkCanceled() { if (published.get()) throw new TaskCanceledException(); }
        })).isInstanceOf(TaskCanceledException.class);
        var evidence = reviews.move(review.plan().id());
        var results = reviews.results(review);
        assertThat(evidence.phase()).isEqualTo(DirectoryTransferMoveRecord.Phase.FALLBACK);
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(new DirectoryTransferQueryService(reviews).get(review.plan().id(), 0, 50, false)
                .review().canAbandonRemainingTransfer()).isFalse();
        assertThatThrownBy(() -> transfers.abandonRemainingTransfer(review.plan().id(), 0))
                .isInstanceOf(StorageAccessException.class);
        assertThat(reviews.move(review.plan().id())).isEqualTo(evidence);
        assertThat(reviews.results(review)).isEqualTo(results);
        assertThat(root.resolve("from/photos/sub/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/sub/b.txt")).hasContent("b");
    }
}
