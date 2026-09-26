package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferFinalizerTest {
    @TempDir Path root;
    NasProperties properties;
    StorageService storage;
    DirectoryTransferReviewStore reviews;
    FileCommitCoordinator commits;
    FileLifecycleService lifecycle;
    DirectoryTransferFinalizer finalizer;
    DirectoryTransferReview review;
    FileCommitJournalStore journals;
    DirectoryTransferExecution execution;

    @BeforeEach void setup() throws IOException {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        reviews = spy(new DirectoryTransferReviewStore(mapper, properties));
        journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        commits = spy(new FileCommitCoordinator(journals, storage, properties));
        lifecycle = mock(FileLifecycleService.class);
        restart();
        file("from/photos/a.txt", "a");
        Files.createDirectory(root.resolve("to"));
    }

    void restart() {
        finalizer = new DirectoryTransferFinalizer(reviews, storage, commits, lifecycle,
                JsonMapper.builder().findAndAddModules().build());
    }

    Path file(String path, String contents) throws IOException {
        Path result = root.resolve(path);
        Files.createDirectories(result.getParent());
        return Files.writeString(result, contents);
    }

    void publish(Operation operation, Map<String, DirectoryTransferReview.Choice> choices) throws IOException {
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(operation, "from/photos", "to", null);
        review = reviews.create(plan);
        var selected = new HashMap<String, DirectoryTransferReview.Choice>();
        for (var item : plan.items()) if (choices.containsKey(item.relativePath())) selected.put(item.id(), choices.get(item.relativePath()));
        if (!selected.isEmpty()) review = reviews.choose(plan.id(), 0, selected);
        var registry = new TemporaryArtifactRegistry();
        execution = spy(new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry));
        execution.publishTransfer(plan.id(), review.revision(), null);
    }

    Map<String, DirectoryTransferCompletion> finish() throws IOException {
        return finalizer.completeTransfer(review.plan().id(), review.revision(), null);
    }

    DirectoryTransferCompletion.Phase phase(Map<String, DirectoryTransferCompletion> results, String relative) {
        String id = review.plan().items().stream().filter(item -> item.relativePath().equals(relative)).findFirst().orElseThrow().id();
        return results.get(id).phase();
    }

    @Test void copyKeepsSourcesAndCompletesJournalsWithoutMovingMetadata() throws Exception {
        publish(Operation.COPY, Map.of());
        var results = finish();
        assertThat(results.values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(journals.list()).isEmpty();
        verifyNoInteractions(lifecycle);
        assertThat(finish()).isEqualTo(results);
    }

    void pauseCopyAfterFirstFile() throws Exception {
        pauseAfterFirstFile(Operation.COPY);
    }

    void pauseAfterFirstFile(Operation operation) throws Exception {
        file("from/photos/b.txt", "b");
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(operation, "from/photos", "to", null);
        review = reviews.create(plan);
        var registry = new TemporaryArtifactRegistry();
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry), commits, storage, registry);
        var published = new AtomicBoolean();
        doAnswer(call -> {
            var value = call.callRealMethod();
            Item item = call.getArgument(1);
            if (item.source().kind() == Kind.FILE) published.set(true);
            return value;
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> transfers().execute(plan.id(), 0, new io.github.fourilla.endervault.storage.StorageProgressListener() {
            @Override public void checkCanceled() {
                if (published.get()) throw new io.github.fourilla.endervault.task.TaskCanceledException();
            }
        })).isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class);
        assertThat(reviews.run(plan.id()).paused()).isTrue();
    }

    @Test void partialMoveAbandonmentPreservesOriginalsAndOnlyClosesPublishedJournals() throws Exception {
        pauseAfterFirstFile(Operation.MOVE);
        String id = review.plan().id();
        var before = reviews.results(review);
        assertThat(new DirectoryTransferQueryService(reviews).get(id, 0, 50, false).review().canAbandonRemainingTransfer()).isTrue();
        assertThat(transfers().abandonRemainingTransfer(id, 0).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(reviews.results(review)).isEqualTo(before);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/b.txt")).hasContent("b");
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
        assertThat(journals.list()).isEmpty();
        for (var result : before.values()) {
            assertThat(reviews.completion(id, result.itemId()).phase()).isEqualTo(DirectoryTransferCompletion.Phase.RETAINED);
        }
        verifyNoInteractions(lifecycle);
        var inspector = new DirectoryTransferMetadataInspector(reviews, JsonMapper.builder().findAndAddModules().build(), journals,
                mock(DirectoryTransferPendingInspector.class));
        assertThat(inspector.inspect(null)).isEmpty();
    }

    @Test void moveAbandonmentRecoveryNeverDeletesSourcesOrPublishesMore() throws Exception {
        pauseAfterFirstFile(Operation.MOVE);
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (!once.getAndSet(true)) throw new IOException("journal cleanup failed");
            return call.callRealMethod();
        }).when(commits).complete(anyString());
        String id = review.plan().id();
        assertThatThrownBy(() -> transfers().abandonRemainingTransfer(id, 0)).isInstanceOf(IOException.class);
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONING);
        clearInvocations(commits);
        new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/b.txt")).hasContent("b");
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
        verify(commits, never()).resumeSingleFile(anyString());
        verifyNoInteractions(lifecycle);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNSTARTED", "PREPARED", "DELETED", "SOURCE_REMOVED", "METADATA_APPLIED", "COMPLETE"})
    void finalizingMoveAbandonmentNeverDeletesAnotherOriginal(String stage) throws Exception {
        publish(Operation.MOVE, Map.of());
        String itemId = review.plan().items().stream().filter(item -> item.relativePath().equals("a.txt")).findFirst().orElseThrow().id();
        DirectoryTransferCompletion state = null;
        if (!stage.equals("UNSTARTED")) {
            state = new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.PREPARED, null);
            reviews.recordCompletion(review.plan().id(), null, state);
        }
        boolean removed = !stage.equals("UNSTARTED") && !stage.equals("PREPARED");
        if (removed) Files.delete(root.resolve("from/photos/a.txt"));
        if (removed && !stage.equals("DELETED")) {
            var next = new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.SOURCE_REMOVED, null);
            reviews.recordCompletion(review.plan().id(), state, next);
            state = next;
        }
        if (stage.equals("METADATA_APPLIED") || stage.equals("COMPLETE")) {
            var next = new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.METADATA_APPLIED, null);
            reviews.recordCompletion(review.plan().id(), state, next);
            state = next;
        }
        if (stage.equals("COMPLETE")) reviews.recordCompletion(review.plan().id(), state,
                new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.COMPLETE, null));
        var publishing = new DirectoryTransferRun(review.plan().id(), review.revision(), DirectoryTransferRun.Phase.PUBLISHING, false);
        reviews.saveRun(null, publishing);
        reviews.saveRun(publishing, new DirectoryTransferRun(review.plan().id(), review.revision(), DirectoryTransferRun.Phase.FINALIZING, true));
        assertThat(new DirectoryTransferQueryService(reviews).get(review.plan().id(), 0, 50, false).review().canAbandonRemainingTransfer()).isTrue();
        assertThat(transfers().abandonRemainingTransfer(review.plan().id(), review.revision()).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(Files.exists(root.resolve("from/photos/a.txt"))).isEqualTo(!removed);
        assertThat(root.resolve("from/photos")).isDirectory();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(journals.list()).isEmpty();
        verify(lifecycle, times(stage.equals("DELETED") || stage.equals("SOURCE_REMOVED") ? 1 : 0))
                .applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
        assertThat(new DirectoryTransferMetadataInspector(reviews, JsonMapper.builder().findAndAddModules().build(), journals,
                mock(DirectoryTransferPendingInspector.class)).inspect(null)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void abandonmentAfterSourceRemovalRecoversMetadataButRejectsRecreatedOriginal(boolean recreated) throws Exception {
        file("from/photos/b.txt", "b");
        publish(Operation.MOVE, Map.of());
        String id = review.plan().id();
        String itemId = review.plan().items().stream().filter(item -> item.relativePath().equals("a.txt")).findFirst().orElseThrow().id();
        var prepared = new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.PREPARED, null);
        reviews.recordCompletion(id, null, prepared);
        Files.delete(root.resolve("from/photos/a.txt"));
        reviews.recordCompletion(id, prepared, new DirectoryTransferCompletion(itemId, DirectoryTransferCompletion.Phase.SOURCE_REMOVED, null));
        var publishing = new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.PUBLISHING, false);
        reviews.saveRun(null, publishing);
        reviews.saveRun(publishing, new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.FINALIZING, true));
        if (recreated) {
            file("from/photos/a.txt", "new original");
            assertThatThrownBy(() -> transfers().abandonRemainingTransfer(id, 0)).hasMessageContaining("new source entry");
            assertThat(reviews.run(id).paused()).isTrue();
            assertThat(root.resolve("from/photos/a.txt")).hasContent("new original");
            verifyNoInteractions(lifecycle);
        } else {
            doThrow(new IOException("metadata unavailable")).doNothing().when(lifecycle)
                    .applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
            assertThatThrownBy(() -> transfers().abandonRemainingTransfer(id, 0)).isInstanceOf(IOException.class);
            assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONING);
            clearInvocations(commits);
            new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover();
            assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
            verify(commits, never()).resumeSingleFile(anyString());
            assertThat(journals.list()).isEmpty();
        }
        assertThat(root.resolve("from/photos/b.txt")).hasContent("b");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos/b.txt", "to/photos/b.txt");
    }

    @Test void abandonedPartialCopyKeepsBothTreesAndNeverPublishesRemainingFile() throws Exception {
        pauseCopyAfterFirstFile();
        String id = review.plan().id();
        var before = reviews.results(review);
        assertThat(new DirectoryTransferQueryService(reviews).get(id, 0, 50, false).review().canAbandonRemainingTransfer()).isTrue();
        clearInvocations(commits);
        assertThat(transfers().abandonRemainingTransfer(id, 0).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThat(reviews.results(review)).isEqualTo(before);
        assertThat(journals.list()).isEmpty();
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/b.txt")).hasContent("b");
        verify(commits, never()).resumeSingleFile(anyString());
        verify(commits, never()).prepareReviewedSingleFile(any(), any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(lifecycle);
        assertThat(transfers().abandonRemainingTransfer(id, 0).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        assertThatThrownBy(() -> transfers().execute(id, 0, null)).hasMessageContaining("abandoned");
        var inspector = new DirectoryTransferMetadataInspector(reviews, JsonMapper.builder().findAndAddModules().build(), journals,
                mock(DirectoryTransferPendingInspector.class));
        assertThat(inspector.inspect(null)).isEmpty();
    }

    @Test void interruptedAbandonmentOnlyRecoversBookkeepingNotPublication() throws Exception {
        pauseCopyAfterFirstFile();
        String id = review.plan().id();
        var once = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            if (!once.getAndSet(true)) throw new IOException("after journal completion");
            return null;
        }).when(commits).complete(anyString());
        assertThatThrownBy(() -> transfers().abandonRemainingTransfer(id, 0)).isInstanceOf(IOException.class);
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONING);
        clearInvocations(commits);
        new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(reviews.run(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
        try (var files = Files.list(root.resolve("to/photos"))) { assertThat(files.count()).isEqualTo(1); }
        verify(commits, never()).resumeSingleFile(anyString());
    }

    @Test void changedPublishedCopyIsNotSilentlyAbandoned() throws Exception {
        pauseCopyAfterFirstFile();
        var result = reviews.results(review).values().stream()
                .filter(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED && r.target().kind() == Kind.FILE).findFirst().orElseThrow();
        Files.writeString(root.resolve(result.targetPath()), "external change");
        assertThatThrownBy(() -> transfers().abandonRemainingTransfer(review.plan().id(), 0)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(journals.list()).isNotEmpty();
    }

    @Test void publicationWithoutResultRequiresRecoveryBeforeAbandonment() throws Exception {
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(Operation.COPY, "from/photos", "to", null);
        review = reviews.create(plan);
        var registry = new TemporaryArtifactRegistry();
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry), commits, storage, registry);
        doAnswer(call -> {
            Item item = call.getArgument(1);
            if (item.source().kind() == Kind.FILE) throw new IOException("before result write");
            return call.callRealMethod();
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> transfers().execute(plan.id(), 0, null)).isInstanceOf(IOException.class);
        reviews.pauseRun(reviews.run(plan.id()), new io.github.fourilla.endervault.task.TaskCanceledException());
        assertThatThrownBy(() -> transfers().abandonRemainingTransfer(plan.id(), 0)).hasMessageContaining("unfinished publication");
        assertThat(reviews.run(plan.id()).phase()).isEqualTo(DirectoryTransferRun.Phase.PUBLISHING);
        assertThat(journals.list()).isNotEmpty();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
    }

    @Test void moveCompletesChildrenBeforeEmptyParentAndReplaysOnce() throws Exception {
        file("from/photos/nested/b.txt", "b");
        Files.createDirectory(root.resolve("from/photos/empty"));
        publish(Operation.MOVE, Map.of());
        var results = finish();
        assertThat(results.values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/nested/b.txt")).hasContent("b");
        var order = inOrder(lifecycle);
        order.verify(lifecycle).applyMovedPathMetadata("from/photos/nested/b.txt", "to/photos/nested/b.txt");
        order.verify(lifecycle).applyMovedPathMetadata("from/photos/nested", "to/photos/nested");
        order.verify(lifecycle).applyMovedPathMetadata("from/photos", "to/photos");
        restart();
        assertThat(finish()).isEqualTo(results);
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos", "to/photos");
        assertThat(journals.list()).isEmpty();
    }

    @Test void skippedChildRetainsParentAndDoesNotMoveRootMetadata() throws Exception {
        file("from/photos/b.txt", "b");
        file("to/photos/a.txt", "old");
        publish(Operation.MOVE, Map.of("a.txt", DirectoryTransferReview.Choice.SKIP));
        var results = finish();
        assertThat(phase(results, "")).isEqualTo(DirectoryTransferCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/b.txt")).doesNotExist();
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void sourceChangedAfterPublicationIsNeverDeleted() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("from/photos/a.txt", "new original");
        var results = finish();
        assertThat(phase(results, "a.txt")).isEqualTo(DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
        assertThat(phase(results, "")).isEqualTo(DirectoryTransferCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new original");
        verifyNoInteractions(lifecycle);
        assertThat(journals.list()).hasSize(1);
    }

    @Test void changedTargetRetainsOriginal() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("to/photos/a.txt", "changed target");
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void newUnplannedSourceEntryPreventsParentRemoval() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("from/photos/new.txt", "new");
        assertThat(phase(finish(), "")).isEqualTo(DirectoryTransferCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/new.txt")).hasContent("new");
        assertThat(root.resolve("from/photos/a.txt")).doesNotExist();
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void crashAfterSourceDeletionCanResumeMetadata() throws Exception {
        publish(Operation.MOVE, Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryTransferCompletion next = call.getArgument(2);
            if (next.phase() == DirectoryTransferCompletion.Phase.SOURCE_REMOVED && !once.getAndSet(true)) {
                throw new IOException("after source deletion, before state update");
            }
            return call.callRealMethod();
        }).when(reviews).recordCompletion(anyString(), any(), any());
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        assertThat(root.resolve("from/photos/a.txt")).doesNotExist();
        verifyNoInteractions(lifecycle);
        restart();
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void metadataFailureResumesButWillNotTouchRecreatedOriginal() throws Exception {
        publish(Operation.MOVE, Map.of());
        doThrow(new IOException("metadata failure")).when(lifecycle).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        file("from/photos/a.txt", "recreated original");
        restart();
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("recreated original");
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void completionBeforeJournalDeletionAvoidsDuplicateMetadataOnRetry() throws Exception {
        publish(Operation.MOVE, Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (!once.getAndSet(true)) throw new IOException("before journal deletion");
            return call.callRealMethod();
        }).when(commits).complete(anyString());
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        restart();
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void sourceParentReplacementIsNotMistakenForOriginalTree() throws Exception {
        publish(Operation.MOVE, Map.of());
        Files.move(root.resolve("from/photos"), root.resolve("from/old-photos"));
        file("from/photos/a.txt", "new tree");
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new tree");
        assertThat(root.resolve("from/old-photos/a.txt")).hasContent("a");
    }

    @Test void transientMetadataFailureRetriesWithoutDeletingAnythingElse() throws Exception {
        publish(Operation.MOVE, Map.of());
        doThrow(new IOException("temporary metadata failure")).doNothing().when(lifecycle)
                .applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        restart();
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        verify(lifecycle, times(2)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void incompletePublicationCannotPermanentlyRetainParents() throws Exception {
        publish(Operation.MOVE, Map.of());
        var completeResults = reviews.results(review);
        var partialResults = new HashMap<>(completeResults);
        var child = review.plan().items().stream().filter(item -> item.relativePath().equals("a.txt")).findFirst().orElseThrow();
        partialResults.remove(child.id());
        doReturn(partialResults).when(reviews).results(any());
        assertThatThrownBy(this::finish).hasMessageContaining("Finish publication");
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        for (var item : review.plan().items()) assertThat(reviews.completion(review.plan().id(), item.id())).isNull();
        verifyNoInteractions(lifecycle);
        doReturn(completeResults).when(reviews).results(any());
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
    }

    DirectoryTransferService transfers() {
        return new DirectoryTransferService(reviews, execution, finalizer);
    }

    @Test void startupIgnoresFrozenReviewsWithoutExplicitRun() throws Exception {
        publish(Operation.MOVE, Map.of());
        var summary = new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(summary.recovered()).isZero();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void startupResumesPublicationAndFinalizesOnce() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryTransferRun(review.plan().id(), review.revision(),
                DirectoryTransferRun.Phase.PUBLISHING, false));
        var recovery = new DirectoryTransferStartupRecoveryService(reviews, transfers(), null);
        assertThat(recovery.recover().recovered()).isEqualTo(1);
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(recovery.recover().recovered()).isZero();
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void failedFinalizationResumesWithoutRepublishing() throws Exception {
        publish(Operation.MOVE, Map.of());
        clearInvocations(execution);
        doThrow(new IOException("metadata failure")).doNothing().when(lifecycle)
                .applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        assertThatThrownBy(() -> transfers().execute(review.plan().id(), review.revision(), null))
                .isInstanceOf(IOException.class);
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.FINALIZING);
        restart();
        assertThat(new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover().recovered()).isEqualTo(1);
        verify(execution, times(1)).publishTransfer(anyString(), anyLong(), any());
        assertThat(root.resolve("from/photos")).doesNotExist();
    }

    @Test void cancellationPausesRunUntilExplicitResume() throws Exception {
        publish(Operation.COPY, Map.of());
        var listener = new io.github.fourilla.endervault.storage.StorageProgressListener() {
            @Override public void checkCanceled() { throw new io.github.fourilla.endervault.task.TaskCanceledException(); }
        };
        assertThatThrownBy(() -> transfers().execute(review.plan().id(), review.revision(), listener))
                .isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class);
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover().recovered()).isZero();
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase())
                .isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
    }

    @Test void taskCancellationKeepsPublishedFilesAndResumesOnlyRemainingWork() throws Exception {
        file("from/photos/b.txt", "b");
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(Operation.COPY, "from/photos", "to", null);
        review = reviews.create(plan);
        var registry = new TemporaryArtifactRegistry();
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry);
        var published = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var firstFile = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> {
            Object value = call.callRealMethod();
            Item item = call.getArgument(1);
            if (item.source().kind() == Kind.FILE && firstFile.compareAndSet(null, item.relativePath())) {
                published.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test timeout");
            }
            return value;
        }).when(reviews).recordResult(any(), any(), any());
        var manager = new io.github.fourilla.endervault.task.TaskManagerService(properties);
        try {
            var transfer = transfers();
            var task = manager.submit(io.github.fourilla.endervault.task.TaskType.FILE_COPY, "Copy", "to", "test", "", context -> {
                transfer.execute(plan.id(), 0, new io.github.fourilla.endervault.storage.StorageProgressListener() {
                    @Override public void checkCanceled() { context.checkCanceled(); }
                });
                return io.github.fourilla.endervault.task.TaskOutcome.complete("Done");
            });
            assertThat(published.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            manager.cancel(task.id());
            release.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (task.active() && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(task.status()).isEqualTo(io.github.fourilla.endervault.task.TaskStatus.CANCELED);
            assertThat(reviews.run(plan.id()).paused()).isTrue();
            String remaining = firstFile.get().equals("a.txt") ? "b.txt" : "a.txt";
            assertThat(root.resolve("to/photos").resolve(firstFile.get())).exists();
            assertThat(root.resolve("to/photos").resolve(remaining)).doesNotExist();
            assertThat(new DirectoryTransferStartupRecoveryService(reviews, transfer, null).recover().recovered()).isZero();
            clearInvocations(commits);
            assertThat(transfer.execute(plan.id(), 0, null).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
            verify(commits, times(1)).prepareReviewedSingleFile(any(), any(), anyString(), anyString(), any(), any());
            assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
            assertThat(root.resolve("to/photos/b.txt")).hasContent("b");
            assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test void interruptedCancellationPersistsPauseAndPreventsStartupResume() throws Exception {
        publish(Operation.COPY, Map.of());
        try {
            assertThatThrownBy(() -> transfers().execute(review.plan().id(), review.revision(),
                    new io.github.fourilla.endervault.storage.StorageProgressListener() {
                        @Override public void checkCanceled() {
                            Thread.currentThread().interrupt();
                            throw new io.github.fourilla.endervault.task.TaskCanceledException();
                        }
                    })).isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class)
                    .hasNoSuppressedExceptions();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover().recovered()).isZero();
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase())
                .isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
    }

    @Test void interruptedIoAlsoPausesButPauseWriteFailureIsNotReportedAsCanceled() throws Exception {
        publish(Operation.COPY, Map.of());
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new java.nio.channels.ClosedByInterruptException();
        }).when(execution).publishTransfer(anyString(), anyLong(), any());
        try {
            assertThatThrownBy(() -> transfers().execute(review.plan().id(), review.revision(), null))
                    .isInstanceOf(io.github.fourilla.endervault.task.TaskCanceledException.class)
                    .hasCauseInstanceOf(java.nio.channels.ClosedByInterruptException.class);
        } finally { Thread.interrupted(); }
        assertThat(reviews.run(review.plan().id()).paused()).isTrue();
        doAnswer(call -> {
            DirectoryTransferRun next = call.getArgument(1);
            if (next.paused()) throw new IOException("Pause storage unavailable");
            return call.callRealMethod();
        }).when(reviews).saveRun(any(), any());
        try {
            assertThatThrownBy(() -> transfers().execute(review.plan().id(), review.revision(), null))
                    .isInstanceOf(IOException.class).hasMessage("Pause storage unavailable");
        } finally { Thread.interrupted(); }
    }

    @Test void startupDefersCorruptRunWithoutTouchingSource() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryTransferRun(review.plan().id(), review.revision(),
                DirectoryTransferRun.Phase.PUBLISHING, false));
        Files.writeString(root.resolve(properties.getStorage().getMetadataDirectory())
                .resolve("directory-merges/runs/" + review.plan().id() + ".json"), "broken");
        assertThat(new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover().deferred()).isEqualTo(1);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void transferPublishesFreshPlanThenFinalizesAndRecordsCompletion() throws Exception {
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(Operation.MOVE, "from/photos", "to", null);
        review = reviews.create(plan);
        var registry = new TemporaryArtifactRegistry();
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry);
        assertThat(transfers().execute(plan.id(), 0, null).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(journals.list()).isEmpty();
    }

    @Test void startupRecordsChangedTargetAsReviewNotSuccess() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryTransferRun(review.plan().id(), review.revision(),
                DirectoryTransferRun.Phase.PUBLISHING, false));
        file("to/photos/a.txt", "externally changed");
        var summary = new DirectoryTransferStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(summary.review()).isEqualTo(1);
        assertThat(summary.recovered()).isZero();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
    }

    DirectoryTransferReplanningService replanner() {
        return new DirectoryTransferReplanningService(reviews, new DirectoryTransferPlanner(storage, properties), storage);
    }

    void approveFile(String relative) throws IOException {
        var item = review.plan().items().stream().filter(i -> i.relativePath().equals(relative)).findFirst().orElseThrow();
        review = reviews.choose(review.plan().id(), review.revision(), Map.of(item.id(), DirectoryTransferReview.Choice.OVERWRITE));
    }

    @Test void copyReplanDoesNotCopySuccessfulSourcesAgainAcrossGenerations() throws Exception {
        file("from/photos/b.txt", "b");
        publish(Operation.COPY, Map.of());
        file("to/photos/a.txt", "external change");
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase()).isEqualTo(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        var first = review;
        review = replanner().replan(first.plan().id(), first.revision(), null);
        assertThat(review.plan().items()).extracting(Item::relativePath).doesNotContain("b.txt");
        assertThat(review.plan().excludedSources()).contains("b.txt");
        assertThat(replanner().replan(first.plan().id(), first.revision(), null)).isEqualTo(review);
        approveFile("a.txt");
        execution.publishTransfer(review.plan().id(), review.revision(), null);
        file("to/photos/a.txt", "another external change");
        transfers().execute(review.plan().id(), review.revision(), null);
        review = replanner().replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().items()).extracting(Item::relativePath).doesNotContain("b.txt");
        assertThat(review.choices()).isEmpty();
        approveFile("a.txt");
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/b.txt")).hasContent("b");
    }

    @Test void moveReplanFinishesRemainingFilesWithoutRepeatingSuccessfulMetadata() throws Exception {
        file("from/photos/b.txt", "b");
        publish(Operation.MOVE, Map.of());
        file("from/photos/a.txt", "changed original");
        transfers().execute(review.plan().id(), review.revision(), null);
        review = replanner().replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().items()).extracting(Item::relativePath).doesNotContain("b.txt");
        approveFile("a.txt");
        transfers().execute(review.plan().id(), review.revision(), null);
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(root.resolve("to/photos/a.txt")).hasContent("changed original");
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/b.txt", "to/photos/b.txt");
    }

    @Test void skippedSourceDoesNotReturnOnReplanOrCauseParentMetadataMove() throws Exception {
        file("from/photos/b.txt", "b");
        file("to/photos/b.txt", "old b");
        publish(Operation.MOVE, Map.of("b.txt", DirectoryTransferReview.Choice.SKIP));
        file("from/photos/a.txt", "changed original");
        transfers().execute(review.plan().id(), review.revision(), null);
        review = replanner().replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().excludedSources()).contains("b.txt");
        Files.delete(root.resolve("from/photos/b.txt"));
        approveFile("a.txt");
        transfers().execute(review.plan().id(), review.revision(), null);
        assertThat(root.resolve("from/photos")).isEmptyDirectory();
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
        assertThat(root.resolve("to/photos/b.txt")).hasContent("old b");
    }

    @Test void transferReplanKeepsPreviouslyRenamedRoot() throws Exception {
        file("to/photos", "existing file");
        publish(Operation.COPY, Map.of("", DirectoryTransferReview.Choice.KEEP_BOTH));
        file("to/photos - 1/a.txt", "externally changed");
        transfers().execute(review.plan().id(), review.revision(), null);
        review = replanner().replan(review.plan().id(), review.revision(), null);
        assertThat(review.plan().targetNames().get("")).isEqualTo("photos - 1");
        approveFile("a.txt");
        transfers().execute(review.plan().id(), review.revision(), null);
        assertThat(root.resolve("to/photos")).hasContent("existing file");
        assertThat(root.resolve("to/photos - 1/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos - 2")).doesNotExist();
    }
}
