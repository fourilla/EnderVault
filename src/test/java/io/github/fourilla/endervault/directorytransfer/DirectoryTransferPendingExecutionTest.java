package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.storage.*;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferPendingExecutionTest {
    @TempDir Path root;
    Path staged;
    PendingFileDecision decision;
    PendingFileDecisionService pending;
    DirectoryTransferReviewStore reviews;
    DirectoryTransferPendingPreparationService preparation;
    DirectoryTransferPendingExecutionService service;
    FileLifecycleService lifecycle;
    DirectoryTransferReview review;
    DirectoryTransferExecution execution;
    DirectoryTransferFinalizer finalizer;
    PendingFileDecisionResolutionObserver observer;
    DirectoryTransferPendingReplanningService replanning;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var storage = new StorageService(properties);
        storage.initialize();
        var artifacts = new TemporaryArtifactRegistry();
        var journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        var commits = new FileCommitCoordinator(journals, storage, properties);
        var repository = new PendingFileDecisionRepository(mapper, properties);
        repository.initialize();
        observer = mock(PendingFileDecisionResolutionObserver.class);
        when(observer.supports(any())).thenReturn(true);
        pending = spy(new PendingFileDecisionService(repository, storage, commits, artifacts, List.of(observer)));
        staged = Files.createDirectory(storage.resolveFileStagingFile("pending-merge-test"));
        Files.writeString(staged.resolve("a.txt"), "uploaded");
        Files.createDirectory(root.resolve("to"));
        decision = pending.create(staged, PendingFileDecisionSource.DIRECTORY_UPLOAD, "to", "photos", 8);
        reviews = spy(new DirectoryTransferReviewStore(mapper, properties));
        replanning = new DirectoryTransferPendingReplanningService(reviews, pending, new DirectoryTransferPlanner(storage, properties), storage);
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, artifacts),
                commits, storage, artifacts);
        lifecycle = mock(FileLifecycleService.class);
        finalizer = new DirectoryTransferFinalizer(reviews, storage, commits, lifecycle, mapper);
        service = new DirectoryTransferPendingExecutionService(pending, reviews, execution, finalizer);
        preparation = new DirectoryTransferPendingPreparationService(pending, new DirectoryTransferPlanner(storage, properties), reviews, service);
    }

    void prepare(Map<String, DirectoryTransferReview.Choice> choices) throws IOException {
        review = preparation.prepare(decision.id(), null);
        var updates = new HashMap<String, DirectoryTransferReview.Choice>();
        for (var item : review.plan().items()) {
            if (choices.containsKey(item.relativePath())) updates.put(item.id(), choices.get(item.relativePath()));
        }
        if (!updates.isEmpty()) review = reviews.choose(review.plan().id(), 0, updates);
    }

    Map<String, DirectoryTransferCompletion> execute() throws IOException {
        return service.execute(review.plan().id(), review.revision(), null);
    }

    void pauseUploadAfterFirstFile() throws Exception {
        Files.writeString(staged.resolve("b.txt"), "second");
        prepare(Map.of());
        var published = new AtomicBoolean();
        doAnswer(call -> {
            var value = call.callRealMethod();
            DirectoryTransferPlan.Item item = call.getArgument(1);
            if (item.source().kind() == DirectoryTransferPlan.Kind.FILE) published.set(true);
            return value;
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> service.execute(review.plan().id(), review.revision(), new StorageProgressListener() {
            @Override public void checkCanceled() {
                if (published.get()) throw new io.github.fourilla.endervault.task.TaskCanceledException();
            }
        })).isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class);
    }

    @Test void partialUploadAbandonmentReturnsWholeStagingAndKeepsPublishedFiles() throws Exception {
        pauseUploadAfterFirstFile();
        String id = review.plan().id();
        assertThat(new DirectoryTransferQueryService(reviews).get(id, 0, 50, false).review().canAbandonRemainingTransfer()).isTrue();
        assertThat(service.abandonRemaining(id, review.revision()).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(staged.resolve("b.txt")).hasContent("second");
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
        assertThat(pending.find(decision.id())).contains(decision);
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        verifyNoInteractions(observer, lifecycle);
        var next = preparation.prepare(decision.id(), null);
        assertThat(next.plan().id()).isNotEqualTo(id);
        service.abandonRemaining(id, review.revision());
        assertThat(pending.directoryMergeOwner(decision.id())).contains(next.plan().id());
    }

    @Test void partialUploadClaimReleaseFailureIsRecoveredWithoutPublishingAgain() throws Exception {
        pauseUploadAfterFirstFile();
        String id = review.plan().id();
        doThrow(new IOException("claim storage unavailable")).doCallRealMethod().when(pending)
                .releaseAbandonedDirectoryMergeClaim(decision.id(), id);
        assertThatThrownBy(() -> service.abandonRemaining(id, review.revision())).isInstanceOf(IOException.class);
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        new DirectoryTransferStartupRecoveryService(reviews, null, service).recover();
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(staged.resolve("b.txt")).hasContent("second");
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test void partialUploadAbandonmentIntentRecoversWithoutFinishingPublication() throws Exception {
        pauseUploadAfterFirstFile();
        String id = review.plan().id();
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryTransferRun next = call.getArgument(1);
            if (next.phase() == DirectoryTransferRun.Phase.ABANDONED && !once.getAndSet(true)) throw new IOException("run storage unavailable");
            return call.callRealMethod();
        }).when(reviews).saveRun(any(), any());
        assertThatThrownBy(() -> service.abandonRemaining(id, review.revision())).isInstanceOf(IOException.class);
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONING);
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        new DirectoryTransferStartupRecoveryService(reviews, null, service).recover();
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(staged.resolve("b.txt")).hasContent("second");
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test void partiallyFinalizedUploadCannotReleaseStagingOwner() throws Exception {
        pauseUploadAfterFirstFile();
        String id = review.plan().id();
        var previous = reviews.run(id);
        reviews.saveRun(previous, new DirectoryTransferRun(id, review.revision(), DirectoryTransferRun.Phase.FINALIZING, true));
        assertThat(new DirectoryTransferQueryService(reviews).get(id, 0, 50, false).review().canAbandonRemainingTransfer()).isFalse();
        assertThatThrownBy(() -> service.abandonRemaining(id, review.revision())).hasMessageContaining("cleanup has already started");
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
    }

    @Test void cancellationAfterFinalizationStartsDoesNotLeaveDuplicateUploadPending() throws Exception {
        prepare(Map.of());
        var finalizing = new AtomicBoolean();
        service.execute(review.plan().id(), review.revision(), new StorageProgressListener() {
            @Override public void onFinalizing() { finalizing.set(true); }
            @Override public void checkCanceled() {
                if (finalizing.get()) throw new io.github.fourilla.endervault.task.TaskCanceledException();
            }
        });
        assertThat(finalizing).isTrue();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(staged).doesNotExist();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
    }

    @Test void changedPublishedTargetStillPreservesStagingDuringNonCancelableFinalization() throws Exception {
        prepare(Map.of());
        service.execute(review.plan().id(), review.revision(), new StorageProgressListener() {
            @Override public void onFinalizing() {
                try { Files.writeString(root.resolve("to/photos/a.txt"), "external change"); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }
        });
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(pending.find(decision.id())).isPresent();
    }

    @Test void finalizationIoFailureRemainsRecoverableRatherThanUserPaused() throws Exception {
        prepare(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryTransferCompletion next = call.getArgument(2);
            if (next.phase() == DirectoryTransferCompletion.Phase.SOURCE_REMOVED && !once.getAndSet(true)) {
                throw new IOException("completion write unavailable");
            }
            return call.callRealMethod();
        }).when(reviews).recordCompletion(anyString(), any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.FINALIZING);
        assertThat(reviews.run(review.plan().id()).paused()).isFalse();
        assertThat(reviews.run(review.plan().id()).recoveryRequired()).isTrue();
        assertThat(new DirectoryTransferQueryService(reviews).get(review.plan().id(), 0, 50, false).review().statusLabel())
                .isEqualTo("Upload merge recovery required (finalization)");
        new DirectoryTransferStartupRecoveryService(reviews, null, service).recover();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(reviews.run(review.plan().id()).recoveryRequired()).isFalse();
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
    }

    @Test void uncontestedUploadMergesIntoExistingDirectoryWithoutAnotherApproval() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/existing.txt"), "keep");
        prepare(Map.of());
        assertThat(service.executeUncontested(review.plan().id(), 0, null).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/existing.txt")).hasContent("keep");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(pending.find(decision.id())).isEmpty();
    }

    @Test void automaticExecutionNeverAppliesEvenFullyChosenConflictsOrResumesPausedWork() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "keep");
        prepare(Map.of("a.txt", DirectoryTransferReview.Choice.OVERWRITE));
        assertThat(review.fullyReviewed()).isTrue();
        assertThat(service.executeUncontested(review.plan().id(), review.revision(), null)).isNull();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("keep");
        assertThat(reviews.run(review.plan().id())).isNull();
        assertThatThrownBy(() -> service.execute(review.plan().id(), review.revision(), new StorageProgressListener() {
            @Override public void checkCanceled() { throw new io.github.fourilla.endervault.task.TaskCanceledException(); }
        })).isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class);
        assertThat(service.executeUncontested(review.plan().id(), review.revision(), null)).isNull();
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
    }

    @Test void newConflictAfterScanRemainsPendingInsteadOfOverwriting() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        prepare(Map.of());
        Files.writeString(root.resolve("to/photos/a.txt"), "new external file");
        assertThat(service.executeUncontested(review.plan().id(), 0, null).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("new external file");
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(pending.find(decision.id())).isPresent();
    }

    @Test void abandonedUploadReturnsToPendingWithoutChangingFilesAndCanBePlannedAgain() throws Exception {
        prepare(Map.of());
        String old = review.plan().id();
        assertThat(new DirectoryTransferQueryService(reviews).get(old, 0, 50, true).review().canAbandon()).isTrue();
        service.abandonUnstarted(old, review.revision());
        service.abandonUnstarted(old, review.revision());
        assertThat(pending.find(decision.id())).contains(decision);
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(root.resolve("to/photos")).doesNotExist();
        assertThat(reviews.run(old).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThatThrownBy(() -> service.execute(old, 0, null)).hasMessageContaining("abandoned");
        verify(observer, never()).afterResolved(any(), any(), anyBoolean(), any());
        review = preparation.prepare(decision.id(), null);
        assertThat(review.plan().id()).isNotEqualTo(old);
        service.abandonUnstarted(old, 0);
        recovery().recover();
        assertThat(pending.directoryMergeOwner(decision.id())).contains(review.plan().id());
        execute();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
    }

    @Test void startupRecoversAbandonmentAfterOwnerReleaseFailure() throws Exception {
        prepare(Map.of());
        String id = review.plan().id();
        doThrow(new IOException("owner release unavailable")).doCallRealMethod().when(pending)
                .releaseAbandonedDirectoryMergeClaim(decision.id(), id);
        assertThatThrownBy(() -> service.abandonUnstarted(id, 0)).isInstanceOf(IOException.class);
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        assertThat(recovery().recover().deferred()).isZero();
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        assertThat(pending.find(decision.id())).contains(decision);
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
    }

    @Test void failedAbandonmentWriteKeepsUploadOwnedUntilDurableRetry() throws Exception {
        prepare(Map.of());
        String id = review.plan().id();
        doThrow(new IOException("intent unavailable")).doCallRealMethod().when(reviews).abandonUnstarted(id, 0);
        assertThatThrownBy(() -> service.abandonUnstarted(id, 0)).isInstanceOf(IOException.class);
        assertThat(reviews.run(id)).isNull();
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        verify(pending, never()).releaseAbandonedDirectoryMergeClaim(anyString(), anyString());
        service.abandonUnstarted(id, 0);
        assertThat(pending.directoryMergeOwner(decision.id())).isEmpty();
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
    }

    @Test void preparationRecoversAbandonedOwnerWithoutWaitingForRestart() throws Exception {
        prepare(Map.of());
        String id = review.plan().id();
        reviews.abandonUnstarted(id, 0); // Crash between durable intent and owner release.
        review = preparation.prepare(decision.id(), null);
        assertThat(review.plan().id()).isNotEqualTo(id);
        assertThat(pending.directoryMergeOwner(decision.id())).contains(review.plan().id());
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
    }

    @Test void staleRevisionOrFrozenApprovalCannotReleaseUploadOwner() throws Exception {
        prepare(Map.of());
        String id = review.plan().id();
        assertThatThrownBy(() -> service.abandonUnstarted(id, 1)).hasMessageContaining("changed");
        assertThat(reviews.run(id)).isNull();
        reviews.freeze(id, 0);
        assertThatThrownBy(() -> service.abandonUnstarted(id, 0)).hasMessageContaining("already started");
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
    }

    @Test void replannedResidualUploadCannotBeReturnedToOrdinaryPending() throws Exception {
        prepare(Map.of());
        Files.writeString(staged.resolve("late.txt"), "retained");
        execute();
        review = replanning.replan(review.plan().id(), review.revision(), null);
        String id = review.plan().id();
        assertThat(new DirectoryTransferQueryService(reviews).get(id, 0, 50, true).review().canAbandon()).isFalse();
        assertThatThrownBy(() -> service.abandonUnstarted(id, review.revision())).hasMessageContaining("partially transferred");
        assertThat(pending.directoryMergeOwner(decision.id())).contains(id);
        assertThat(staged.resolve("late.txt")).hasContent("retained");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
    }

    @Test void interruptedPendingMergeKeepsOwnerAndWaitsForExplicitResume() throws Exception {
        prepare(Map.of());
        try {
            assertThatThrownBy(() -> service.execute(review.plan().id(), review.revision(), new StorageProgressListener() {
                @Override public void checkCanceled() {
                    Thread.currentThread().interrupt();
                    throw new io.github.fourilla.endervault.task.TaskCanceledException();
                }
            })).isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class)
                    .hasNoSuppressedExceptions();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(pending.find(decision.id())).isPresent();
        assertThat(staged.resolve("a.txt")).hasContent("uploaded");
        assertThat(service.recover(review.plan().id()).paused()).isTrue();
        assertThat(root.resolve("to/photos")).doesNotExist();
        execute();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
    }

    @Test void publishesAndCleansUploadWithoutMovingVaultMetadata() throws Exception {
        prepare(Map.of());
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(staged).doesNotExist();
        verifyNoInteractions(lifecycle);
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
    }

    @Test void explicitDiscardPreservesDestinationAndDeletesOnlyApprovedUpload() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "original");
        prepare(Map.of("a.txt", DirectoryTransferReview.Choice.DISCARD_UPLOAD));
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("original");
        assertThat(staged).doesNotExist();
        verifyNoInteractions(lifecycle);
    }

    @Test void changedDiscardSourceIsRetainedForReview() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "original");
        prepare(Map.of("a.txt", DirectoryTransferReview.Choice.DISCARD_UPLOAD));
        Files.writeString(staged.resolve("a.txt"), "changed upload");
        assertThat(execute().values()).anyMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
        assertThat(staged.resolve("a.txt")).hasContent("changed upload");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("original");
    }

    @Test void directoryDiscardLeavesUnplannedChildrenUntouched() throws Exception {
        Files.createDirectory(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested/b.txt"), "old upload");
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/nested"), "destination file");
        prepare(Map.of("nested", DirectoryTransferReview.Choice.DISCARD_UPLOAD));
        Files.writeString(staged.resolve("nested/new.txt"), "new upload");
        assertThat(execute().values()).anyMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.RETAINED);
        assertThat(staged.resolve("nested/new.txt")).hasContent("new upload");
        assertThat(staged.resolve("nested/b.txt")).doesNotExist();
        assertThat(root.resolve("to/photos/nested")).hasContent("destination file");
    }

    @Test void resumesAfterDeletionBeforeCompletionWrite() throws Exception {
        prepare(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryTransferCompletion next = call.getArgument(2);
            if (next.phase() == DirectoryTransferCompletion.Phase.SOURCE_REMOVED && !once.getAndSet(true)) {
                throw new IOException("injected after delete");
            }
            return call.callRealMethod();
        }).when(reviews).recordCompletion(anyString(), any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        service = new DirectoryTransferPendingExecutionService(pending, reviews, execution, finalizer);
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(staged).doesNotExist();
    }

    DirectoryTransferStartupRecoveryService recovery() {
        return new DirectoryTransferStartupRecoveryService(reviews, null, service);
    }

    @Test void startupResumesAfterPendingRemovalBeforeRunCompletion() throws Exception {
        prepare(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            if (!once.getAndSet(true)) throw new IOException("after pending removal");
            return null;
        }).when(pending).completeDirectoryMerge(anyString(), anyString(), any(), anyBoolean());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.OWNER_COMPLETING);
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        assertThat(recovery().recover().recovered()).isZero();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(staged).doesNotExist();
    }

    @Test void observerFailureIsRetriedAfterPendingRemoval() throws Exception {
        prepare(Map.of());
        doThrow(new IOException("observer unavailable")).doNothing().when(observer)
                .afterResolved(any(), eq(PendingFileDecisionAction.MERGE), eq(false), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(recovery().recover().recovered()).isEqualTo(1);
        verify(observer, times(2)).afterResolved(any(), eq(PendingFileDecisionAction.MERGE), eq(false), any());
    }

    @Test void retainedUploadRemainsPendingAndDoesNotNotifyCompletion() throws Exception {
        prepare(Map.of());
        Files.writeString(staged.resolve("new.txt"), "new data");
        execute();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        assertThat(pending.find(decision.id())).isPresent();
        assertThat(staged.resolve("new.txt")).hasContent("new data");
        verify(observer, never()).afterResolved(any(), any(), anyBoolean(), any());
        assertThat(recovery().recover().recovered()).isZero();
    }

    @Test void rootDiscardCompletesWithoutRequiringDestinationDirectory() throws Exception {
        Files.writeString(root.resolve("to/photos"), "existing file");
        prepare(Map.of("", DirectoryTransferReview.Choice.DISCARD_UPLOAD));
        execute();
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(root.resolve("to/photos")).hasContent("existing file");
        verify(observer).afterResolved(any(), eq(PendingFileDecisionAction.MERGE), eq(true), isNull());
    }

    @Test void replanUsesOnlyRemainingUploadAndDropsOldDecisions() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "existing");
        Files.writeString(staged.resolve("b.txt"), "second");
        prepare(Map.of("a.txt", DirectoryTransferReview.Choice.OVERWRITE));
        Files.writeString(staged.resolve("a.txt"), "changed after review");
        execute();
        var old = review;
        review = replanning.replan(old.plan().id(), old.revision(), null);
        assertThat(review.choices()).isEmpty();
        assertThat(review.fullyReviewed()).isFalse();
        assertThat(review.plan().items()).extracting(DirectoryTransferPlan.Item::relativePath).doesNotContain("b.txt");
        assertThat(replanning.replan(old.plan().id(), old.revision(), null)).isEqualTo(review);
        var item = review.plan().items().stream().filter(i -> i.relativePath().equals("a.txt")).findFirst().orElseThrow();
        review = reviews.choose(review.plan().id(), 0, Map.of(item.id(), DirectoryTransferReview.Choice.OVERWRITE));
        execute();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("changed after review");
        assertThat(root.resolve("to/photos/b.txt")).hasContent("second");
        assertThat(pending.find(decision.id())).isEmpty();
    }

    @Test void replanPreservesNestedKeepBothDestination() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/nested"), "existing file");
        Files.createDirectory(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested/b.txt"), "first child");
        prepare(Map.of("nested", DirectoryTransferReview.Choice.KEEP_BOTH));
        Files.writeString(staged.resolve("nested/new.txt"), "late child");
        execute();
        review = replanning.replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().targetNames().get("nested")).isEqualTo("nested - 1");
        execute();
        assertThat(root.resolve("to/photos/nested")).hasContent("existing file");
        assertThat(root.resolve("to/photos/nested - 1/b.txt")).hasContent("first child");
        assertThat(root.resolve("to/photos/nested - 1/new.txt")).hasContent("late child");
        assertThat(root.resolve("to/photos/nested - 2")).doesNotExist();
    }

    @Test void failedOwnerHandoffResumesSameSuccessor() throws Exception {
        prepare(Map.of());
        Files.writeString(staged.resolve("new.txt"), "late child");
        execute();
        var old = review;
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (!once.getAndSet(true)) throw new IOException("before claim replacement");
            return call.callRealMethod();
        }).when(pending).transferDirectoryMergeOwner(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> replanning.replan(old.plan().id(), old.revision(), null)).isInstanceOf(IOException.class);
        String next = reviews.successor(old.plan().id());
        assertThat(pending.directoryMergeOwner(decision.id())).contains(old.plan().id());
        review = replanning.replan(old.plan().id(), old.revision(), null);
        assertThat(review.plan().id()).isEqualTo(next);
        assertThat(reviews.ids()).hasSize(2);
        assertThat(pending.directoryMergeOwner(decision.id())).contains(next);
        execute();
        assertThat(root.resolve("to/photos/new.txt")).hasContent("late child");
    }

    @Test void replanPreservesRootKeepBothDestination() throws Exception {
        Files.writeString(root.resolve("to/photos"), "original root file");
        prepare(Map.of("", DirectoryTransferReview.Choice.KEEP_BOTH));
        Files.writeString(staged.resolve("new.txt"), "late child");
        execute();
        review = replanning.replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().targetNames().get("")).isEqualTo("photos - 1");
        execute();
        assertThat(root.resolve("to/photos")).hasContent("original root file");
        assertThat(root.resolve("to/photos - 1/a.txt")).hasContent("uploaded");
        assertThat(root.resolve("to/photos - 1/new.txt")).hasContent("late child");
        assertThat(root.resolve("to/photos - 2")).doesNotExist();
    }

    @Test void unresolvedMissingSourceCannotDisappearFromNewReview() throws Exception {
        prepare(Map.of());
        Files.writeString(staged.resolve("a.txt"), "changed upload");
        execute();
        Files.delete(staged.resolve("a.txt"));
        assertThatThrownBy(() -> replanning.replan(review.plan().id(), review.revision(), null))
                .hasMessageContaining("manual recovery");
        assertThat(pending.directoryMergeOwner(decision.id())).contains(review.plan().id());
        assertThat(reviews.successor(review.plan().id())).isNull();
    }
}
