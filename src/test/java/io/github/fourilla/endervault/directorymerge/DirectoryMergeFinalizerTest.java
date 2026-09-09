package io.github.fourilla.endervault.directorymerge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;

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

class DirectoryMergeFinalizerTest {
    @TempDir Path root;
    NasProperties properties;
    StorageService storage;
    DirectoryMergeReviewStore reviews;
    FileCommitCoordinator commits;
    FileLifecycleService lifecycle;
    DirectoryMergeFinalizer finalizer;
    DirectoryMergeReview review;
    FileCommitJournalStore journals;
    DirectoryMergeExecution execution;

    @BeforeEach void setup() throws IOException {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        reviews = spy(new DirectoryMergeReviewStore(mapper, properties));
        journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        commits = spy(new FileCommitCoordinator(journals, storage, properties));
        lifecycle = mock(FileLifecycleService.class);
        restart();
        file("from/photos/a.txt", "a");
        Files.createDirectory(root.resolve("to"));
    }

    void restart() {
        finalizer = new DirectoryMergeFinalizer(reviews, storage, commits, lifecycle,
                JsonMapper.builder().findAndAddModules().build());
    }

    Path file(String path, String contents) throws IOException {
        Path result = root.resolve(path);
        Files.createDirectories(result.getParent());
        return Files.writeString(result, contents);
    }

    void publish(Operation operation, Map<String, DirectoryMergeReview.Choice> choices) throws IOException {
        var plan = new DirectoryMergePlanner(storage, properties).planTransfer(operation, "from/photos", "to", null);
        review = reviews.create(plan);
        var selected = new HashMap<String, DirectoryMergeReview.Choice>();
        for (var item : plan.items()) if (choices.containsKey(item.relativePath())) selected.put(item.id(), choices.get(item.relativePath()));
        if (!selected.isEmpty()) review = reviews.choose(plan.id(), 0, selected);
        var registry = new TemporaryArtifactRegistry();
        execution = spy(new DirectoryMergeExecution(reviews, new DirectoryMergeFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry));
        execution.publishTransfer(plan.id(), review.revision(), null);
    }

    Map<String, DirectoryMergeCompletion> finish() throws IOException {
        return finalizer.completeTransfer(review.plan().id(), review.revision(), null);
    }

    DirectoryMergeCompletion.Phase phase(Map<String, DirectoryMergeCompletion> results, String relative) {
        String id = review.plan().items().stream().filter(item -> item.relativePath().equals(relative)).findFirst().orElseThrow().id();
        return results.get(id).phase();
    }

    @Test void copyKeepsSourcesAndCompletesJournalsWithoutMovingMetadata() throws Exception {
        publish(Operation.COPY, Map.of());
        var results = finish();
        assertThat(results.values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(journals.list()).isEmpty();
        verifyNoInteractions(lifecycle);
        assertThat(finish()).isEqualTo(results);
    }

    @Test void moveCompletesChildrenBeforeEmptyParentAndReplaysOnce() throws Exception {
        file("from/photos/nested/b.txt", "b");
        Files.createDirectory(root.resolve("from/photos/empty"));
        publish(Operation.MOVE, Map.of());
        var results = finish();
        assertThat(results.values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
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
        publish(Operation.MOVE, Map.of("a.txt", DirectoryMergeReview.Choice.SKIP));
        var results = finish();
        assertThat(phase(results, "")).isEqualTo(DirectoryMergeCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos/b.txt")).doesNotExist();
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void sourceChangedAfterPublicationIsNeverDeleted() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("from/photos/a.txt", "new original");
        var results = finish();
        assertThat(phase(results, "a.txt")).isEqualTo(DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
        assertThat(phase(results, "")).isEqualTo(DirectoryMergeCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new original");
        verifyNoInteractions(lifecycle);
        assertThat(journals.list()).hasSize(1);
    }

    @Test void changedTargetRetainsOriginal() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("to/photos/a.txt", "changed target");
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void newUnplannedSourceEntryPreventsParentRemoval() throws Exception {
        publish(Operation.MOVE, Map.of());
        file("from/photos/new.txt", "new");
        assertThat(phase(finish(), "")).isEqualTo(DirectoryMergeCompletion.Phase.RETAINED);
        assertThat(root.resolve("from/photos/new.txt")).hasContent("new");
        assertThat(root.resolve("from/photos/a.txt")).doesNotExist();
        verify(lifecycle, never()).applyMovedPathMetadata("from/photos", "to/photos");
    }

    @Test void crashAfterSourceDeletionCanResumeMetadata() throws Exception {
        publish(Operation.MOVE, Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryMergeCompletion next = call.getArgument(2);
            if (next.phase() == DirectoryMergeCompletion.Phase.SOURCE_REMOVED && !once.getAndSet(true)) {
                throw new IOException("after source deletion, before state update");
            }
            return call.callRealMethod();
        }).when(reviews).recordCompletion(anyString(), any(), any());
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        assertThat(root.resolve("from/photos/a.txt")).doesNotExist();
        verifyNoInteractions(lifecycle);
        restart();
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void metadataFailureResumesButWillNotTouchRecreatedOriginal() throws Exception {
        publish(Operation.MOVE, Map.of());
        doThrow(new IOException("metadata failure")).when(lifecycle).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        file("from/photos/a.txt", "recreated original");
        restart();
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
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
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        verify(lifecycle, times(1)).applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
    }

    @Test void sourceParentReplacementIsNotMistakenForOriginalTree() throws Exception {
        publish(Operation.MOVE, Map.of());
        Files.move(root.resolve("from/photos"), root.resolve("from/old-photos"));
        file("from/photos/a.txt", "new tree");
        assertThat(phase(finish(), "a.txt")).isEqualTo(DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new tree");
        assertThat(root.resolve("from/old-photos/a.txt")).hasContent("a");
    }

    @Test void transientMetadataFailureRetriesWithoutDeletingAnythingElse() throws Exception {
        publish(Operation.MOVE, Map.of());
        doThrow(new IOException("temporary metadata failure")).doNothing().when(lifecycle)
                .applyMovedPathMetadata("from/photos/a.txt", "to/photos/a.txt");
        assertThatThrownBy(this::finish).isInstanceOf(IOException.class);
        restart();
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
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
        assertThat(finish().values()).allMatch(r -> r.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
    }

    DirectoryMergeTransferService transfers() {
        return new DirectoryMergeTransferService(reviews, execution, finalizer);
    }

    @Test void startupIgnoresFrozenReviewsWithoutExplicitRun() throws Exception {
        publish(Operation.MOVE, Map.of());
        var summary = new DirectoryMergeStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(summary.recovered()).isZero();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void startupResumesPublicationAndFinalizesOnce() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryMergeRun(review.plan().id(), review.revision(),
                DirectoryMergeRun.Phase.PUBLISHING, false));
        var recovery = new DirectoryMergeStartupRecoveryService(reviews, transfers(), null);
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
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryMergeRun.Phase.FINALIZING);
        restart();
        assertThat(new DirectoryMergeStartupRecoveryService(reviews, transfers(), null).recover().recovered()).isEqualTo(1);
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
        assertThat(new DirectoryMergeStartupRecoveryService(reviews, transfers(), null).recover().recovered()).isZero();
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase())
                .isEqualTo(DirectoryMergeRun.Phase.COMPLETE);
    }

    @Test void startupDefersCorruptRunWithoutTouchingSource() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryMergeRun(review.plan().id(), review.revision(),
                DirectoryMergeRun.Phase.PUBLISHING, false));
        Files.writeString(root.resolve(properties.getStorage().getMetadataDirectory())
                .resolve("directory-merges/runs/" + review.plan().id() + ".json"), "broken");
        assertThat(new DirectoryMergeStartupRecoveryService(reviews, transfers(), null).recover().deferred()).isEqualTo(1);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        verifyNoInteractions(lifecycle);
    }

    @Test void transferPublishesFreshPlanThenFinalizesAndRecordsCompletion() throws Exception {
        var plan = new DirectoryMergePlanner(storage, properties).planTransfer(Operation.MOVE, "from/photos", "to", null);
        review = reviews.create(plan);
        var registry = new TemporaryArtifactRegistry();
        execution = new DirectoryMergeExecution(reviews, new DirectoryMergeFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry);
        assertThat(transfers().execute(plan.id(), 0, null).phase()).isEqualTo(DirectoryMergeRun.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
        assertThat(root.resolve("from/photos")).doesNotExist();
        assertThat(journals.list()).isEmpty();
    }

    @Test void startupRecordsChangedTargetAsReviewNotSuccess() throws Exception {
        publish(Operation.MOVE, Map.of());
        reviews.saveRun(null, new DirectoryMergeRun(review.plan().id(), review.revision(),
                DirectoryMergeRun.Phase.PUBLISHING, false));
        file("to/photos/a.txt", "externally changed");
        var summary = new DirectoryMergeStartupRecoveryService(reviews, transfers(), null).recover();
        assertThat(summary.review()).isEqualTo(1);
        assertThat(summary.recovered()).isZero();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("a");
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryMergeRun.Phase.NEEDS_REVIEW);
    }

    DirectoryMergeTransferReplanningService replanner() {
        return new DirectoryMergeTransferReplanningService(reviews, new DirectoryMergePlanner(storage, properties), storage);
    }

    void approveFile(String relative) throws IOException {
        var item = review.plan().items().stream().filter(i -> i.relativePath().equals(relative)).findFirst().orElseThrow();
        review = reviews.choose(review.plan().id(), review.revision(), Map.of(item.id(), DirectoryMergeReview.Choice.OVERWRITE));
    }

    @Test void copyReplanDoesNotCopySuccessfulSourcesAgainAcrossGenerations() throws Exception {
        file("from/photos/b.txt", "b");
        publish(Operation.COPY, Map.of());
        file("to/photos/a.txt", "external change");
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase()).isEqualTo(DirectoryMergeRun.Phase.NEEDS_REVIEW);
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
        assertThat(transfers().execute(review.plan().id(), review.revision(), null).phase()).isEqualTo(DirectoryMergeRun.Phase.COMPLETE);
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
        publish(Operation.MOVE, Map.of("b.txt", DirectoryMergeReview.Choice.SKIP));
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
        publish(Operation.COPY, Map.of("", DirectoryMergeReview.Choice.KEEP_BOTH));
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
