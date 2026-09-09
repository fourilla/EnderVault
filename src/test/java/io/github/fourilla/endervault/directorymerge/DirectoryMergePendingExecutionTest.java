package io.github.fourilla.endervault.directorymerge;

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

class DirectoryMergePendingExecutionTest {
    @TempDir Path root;
    Path staged;
    PendingFileDecision decision;
    PendingFileDecisionService pending;
    DirectoryMergeReviewStore reviews;
    DirectoryMergePendingPreparationService preparation;
    DirectoryMergePendingExecutionService service;
    FileLifecycleService lifecycle;
    DirectoryMergeReview review;
    DirectoryMergeExecution execution;
    DirectoryMergeFinalizer finalizer;
    PendingFileDecisionResolutionObserver observer;
    DirectoryMergePendingReplanningService replanning;

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
        reviews = spy(new DirectoryMergeReviewStore(mapper, properties));
        preparation = new DirectoryMergePendingPreparationService(pending, new DirectoryMergePlanner(storage, properties), reviews);
        replanning = new DirectoryMergePendingReplanningService(reviews, pending, new DirectoryMergePlanner(storage, properties), storage);
        execution = new DirectoryMergeExecution(reviews, new DirectoryMergeFilePublisher(reviews, storage, commits, artifacts),
                commits, storage, artifacts);
        lifecycle = mock(FileLifecycleService.class);
        finalizer = new DirectoryMergeFinalizer(reviews, storage, commits, lifecycle, mapper);
        service = new DirectoryMergePendingExecutionService(pending, reviews, execution, finalizer);
    }

    void prepare(Map<String, DirectoryMergeReview.Choice> choices) throws IOException {
        review = preparation.prepare(decision.id(), null);
        var updates = new HashMap<String, DirectoryMergeReview.Choice>();
        for (var item : review.plan().items()) {
            if (choices.containsKey(item.relativePath())) updates.put(item.id(), choices.get(item.relativePath()));
        }
        if (!updates.isEmpty()) review = reviews.choose(review.plan().id(), 0, updates);
    }

    Map<String, DirectoryMergeCompletion> execute() throws IOException {
        return service.execute(review.plan().id(), review.revision(), null);
    }

    @Test void publishesAndCleansUploadWithoutMovingVaultMetadata() throws Exception {
        prepare(Map.of());
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(staged).doesNotExist();
        verifyNoInteractions(lifecycle);
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryMergeRun.Phase.COMPLETE);
    }

    @Test void explicitDiscardPreservesDestinationAndDeletesOnlyApprovedUpload() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "original");
        prepare(Map.of("a.txt", DirectoryMergeReview.Choice.DISCARD_UPLOAD));
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("original");
        assertThat(staged).doesNotExist();
        verifyNoInteractions(lifecycle);
    }

    @Test void changedDiscardSourceIsRetainedForReview() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "original");
        prepare(Map.of("a.txt", DirectoryMergeReview.Choice.DISCARD_UPLOAD));
        Files.writeString(staged.resolve("a.txt"), "changed upload");
        assertThat(execute().values()).anyMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
        assertThat(staged.resolve("a.txt")).hasContent("changed upload");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("original");
    }

    @Test void directoryDiscardLeavesUnplannedChildrenUntouched() throws Exception {
        Files.createDirectory(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested/b.txt"), "old upload");
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/nested"), "destination file");
        prepare(Map.of("nested", DirectoryMergeReview.Choice.DISCARD_UPLOAD));
        Files.writeString(staged.resolve("nested/new.txt"), "new upload");
        assertThat(execute().values()).anyMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.RETAINED);
        assertThat(staged.resolve("nested/new.txt")).hasContent("new upload");
        assertThat(staged.resolve("nested/b.txt")).doesNotExist();
        assertThat(root.resolve("to/photos/nested")).hasContent("destination file");
    }

    @Test void resumesAfterDeletionBeforeCompletionWrite() throws Exception {
        prepare(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            DirectoryMergeCompletion next = call.getArgument(2);
            if (next.phase() == DirectoryMergeCompletion.Phase.SOURCE_REMOVED && !once.getAndSet(true)) {
                throw new IOException("injected after delete");
            }
            return call.callRealMethod();
        }).when(reviews).recordCompletion(anyString(), any(), any());
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
        service = new DirectoryMergePendingExecutionService(pending, reviews, execution, finalizer);
        assertThat(execute().values()).allMatch(c -> c.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("uploaded");
        assertThat(staged).doesNotExist();
    }

    DirectoryMergeStartupRecoveryService recovery() {
        return new DirectoryMergeStartupRecoveryService(reviews, null, service);
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
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryMergeRun.Phase.OWNER_COMPLETING);
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
        assertThat(reviews.run(review.plan().id()).phase()).isEqualTo(DirectoryMergeRun.Phase.NEEDS_REVIEW);
        assertThat(pending.find(decision.id())).isPresent();
        assertThat(staged.resolve("new.txt")).hasContent("new data");
        verify(observer, never()).afterResolved(any(), any(), anyBoolean(), any());
        assertThat(recovery().recover().recovered()).isZero();
    }

    @Test void rootDiscardCompletesWithoutRequiringDestinationDirectory() throws Exception {
        Files.writeString(root.resolve("to/photos"), "existing file");
        prepare(Map.of("", DirectoryMergeReview.Choice.DISCARD_UPLOAD));
        execute();
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(root.resolve("to/photos")).hasContent("existing file");
        verify(observer).afterResolved(any(), eq(PendingFileDecisionAction.MERGE), eq(true), isNull());
    }

    @Test void replanUsesOnlyRemainingUploadAndDropsOldDecisions() throws Exception {
        Files.createDirectory(root.resolve("to/photos"));
        Files.writeString(root.resolve("to/photos/a.txt"), "existing");
        Files.writeString(staged.resolve("b.txt"), "second");
        prepare(Map.of("a.txt", DirectoryMergeReview.Choice.OVERWRITE));
        Files.writeString(staged.resolve("a.txt"), "changed after review");
        execute();
        var old = review;
        review = replanning.replan(old.plan().id(), old.revision(), null);
        assertThat(review.choices()).isEmpty();
        assertThat(review.fullyReviewed()).isFalse();
        assertThat(review.plan().items()).extracting(DirectoryMergePlan.Item::relativePath).doesNotContain("b.txt");
        assertThat(replanning.replan(old.plan().id(), old.revision(), null)).isEqualTo(review);
        var item = review.plan().items().stream().filter(i -> i.relativePath().equals("a.txt")).findFirst().orElseThrow();
        review = reviews.choose(review.plan().id(), 0, Map.of(item.id(), DirectoryMergeReview.Choice.OVERWRITE));
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
        prepare(Map.of("nested", DirectoryMergeReview.Choice.KEEP_BOTH));
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
        prepare(Map.of("", DirectoryMergeReview.Choice.KEEP_BOTH));
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
