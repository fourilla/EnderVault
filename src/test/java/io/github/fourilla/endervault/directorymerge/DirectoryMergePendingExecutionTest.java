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
        pending = new PendingFileDecisionService(repository, storage, commits, artifacts, List.of());
        staged = Files.createDirectory(storage.resolveFileStagingFile("pending-merge-test"));
        Files.writeString(staged.resolve("a.txt"), "uploaded");
        Files.createDirectory(root.resolve("to"));
        decision = pending.create(staged, PendingFileDecisionSource.DIRECTORY_UPLOAD, "to", "photos", 8);
        reviews = spy(new DirectoryMergeReviewStore(mapper, properties));
        preparation = new DirectoryMergePendingPreparationService(pending, new DirectoryMergePlanner(storage, properties), reviews);
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
        assertThat(pending.require(decision.id())).isEqualTo(decision);
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
}
