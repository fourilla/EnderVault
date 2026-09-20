package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferQueryServiceTest {
    @TempDir Path root;
    DirectoryTransferReviewStore store;
    DirectoryTransferQueryService query;
    DirectoryTransferReview review;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var storage = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storage.initialize();
        Files.createDirectories(root.resolve("source/photos"));
        Files.createDirectories(root.resolve("destination/photos"));
        Files.writeString(root.resolve("source/photos/a.txt"), "new");
        Files.writeString(root.resolve("source/photos/b.txt"), "new");
        Files.writeString(root.resolve("destination/photos/a.txt"), "old");
        store = new DirectoryTransferReviewStore(JsonMapper.builder().findAndAddModules().build(), properties);
        review = store.create(new DirectoryTransferPlanner(storage, properties)
                .planTransfer(DirectoryTransferPlan.Operation.COPY, "source/photos", "destination", null));
        query = new DirectoryTransferQueryService(store);
    }

    @Test void durableListingFindsReviewWithoutAnyTaskHistory() throws Exception {
        var page = new DirectoryTransferQueryService(store).list(0, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().id()).isEqualTo(review.plan().id());
        assertThat(page.items().getFirst().editable()).isTrue();
    }

    @Test void pagesAndFiltersConflictsWithoutExposingFilesystemIdentity() throws Exception {
        var all = query.get(review.plan().id(), 1, 1, false);
        assertThat(all.entries().total()).isEqualTo(3);
        assertThat(all.entries().items()).hasSize(1);
        var conflicts = query.get(review.plan().id(), 0, 10, true);
        assertThat(conflicts.entries().total()).isEqualTo(1);
        assertThat(conflicts.entries().items().getFirst().relativePath()).isEqualTo("a.txt");
        String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(conflicts);
        assertThat(json).doesNotContain("fileKey", "stagingFilename", "excludedSources", "targetNames");
        assertThat(query.get(review.plan().id(), Integer.MAX_VALUE, 200, false).entries().items()).isEmpty();
    }

    @Test void frozenReviewIsNotEditableEvenBeforeRunRecordExists() throws Exception {
        String conflict = query.get(review.plan().id(), 0, 10, true).entries().items().getFirst().id();
        store.choose(review.plan().id(), 0, Map.of(conflict, DirectoryTransferReview.Choice.SKIP));
        store.freeze(review.plan().id(), 1);
        var result = query.get(review.plan().id(), 0, 10, true).review();
        assertThat(result.editable()).isFalse();
        assertThat(result.run()).isNull();
        assertThat(result.fullyReviewed()).isTrue();
    }

    @Test void invalidPaginationRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> query.list(-1, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> query.list(0, 201));
        assertThatIllegalArgumentException().isThrownBy(() -> query.get(review.plan().id(), 0, 0, false));
    }

    @Test void executionViewShowsRemainingPublicationAndFinalizationInsteadOfOnlyConflicts() throws Exception {
        String id = review.plan().id();
        var items = review.plan().items();
        var a = items.stream().filter(i -> i.relativePath().equals("a.txt")).findFirst().orElseThrow();
        var b = items.stream().filter(i -> i.relativePath().equals("b.txt")).findFirst().orElseThrow();
        var parent = items.stream().filter(i -> i.relativePath().isEmpty()).findFirst().orElseThrow();
        store.choose(id, 0, Map.of(a.id(), DirectoryTransferReview.Choice.OVERWRITE));
        store.freeze(id, 1);
        store.saveRun(null, new DirectoryTransferRun(id, 1, DirectoryTransferRun.Phase.PUBLISHING, true));
        store.recordResult(review, parent, new DirectoryTransferResult(parent.id(), DirectoryTransferResult.Status.PUBLISHED,
                "destination/photos", parent.source(), null, null));
        var detail = query.get(id, 0, 1, true, true);
        assertThat(detail.executionView()).isTrue();
        assertThat(detail.entries().total()).isEqualTo(3);
        assertThat(detail.entries().items()).hasSize(1);
        assertThat(detail.review().statusLabel()).isEqualTo("Copy paused (publication)");
        var rows = query.get(id, 0, 50, true, true).entries().items();
        assertThat(rows).filteredOn(e -> e.id().equals(b.id())).extracting(DirectoryTransferQueryService.Entry::plannedTargetPath)
                .containsExactly("destination/photos/b.txt");
        assertThat(rows).filteredOn(e -> e.id().equals(parent.id())).extracting(DirectoryTransferQueryService.Entry::stage)
                .containsExactly(DirectoryTransferQueryService.EntryStage.FINALIZATION_PENDING);
        assertThat(rows).filteredOn(e -> e.id().equals(b.id())).extracting(DirectoryTransferQueryService.Entry::stage)
                .containsExactly(DirectoryTransferQueryService.EntryStage.PUBLICATION_PENDING);
        store.recordCompletion(id, null, new DirectoryTransferCompletion(b.id(), DirectoryTransferCompletion.Phase.NEEDS_REVIEW, "changed"));
        assertThat(query.get(id, 0, 50, true, true).entries().items()).filteredOn(e -> e.id().equals(b.id()))
                .extracting(DirectoryTransferQueryService.Entry::stage).containsExactly(DirectoryTransferQueryService.EntryStage.NEEDS_REVIEW);
        var state = new DirectoryTransferCompletion(parent.id(), DirectoryTransferCompletion.Phase.PREPARED, null);
        store.recordCompletion(id, null, state);
        var next = new DirectoryTransferCompletion(parent.id(), DirectoryTransferCompletion.Phase.METADATA_APPLIED, null);
        store.recordCompletion(id, state, next);
        store.recordCompletion(id, next, new DirectoryTransferCompletion(parent.id(), DirectoryTransferCompletion.Phase.COMPLETE, null));
        assertThat(query.get(id, 0, 50, true, true).entries().total()).isEqualTo(2);
        assertThat(query.get(id, 0, 50, true, true).entries().items()).extracting(DirectoryTransferQueryService.Entry::id).doesNotContain(parent.id());
        String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(detail);
        assertThat(json).contains("statusLabel", "Directory copy", "executionView")
                .doesNotContain("fileKey", "stagingFilename", "commitId");
    }

    @Test void remainingFlagDoesNotReplaceEditableConflictReview() throws Exception {
        var result = query.get(review.plan().id(), 0, 50, true, true);
        assertThat(result.executionView()).isFalse();
        assertThat(result.entries().total()).isEqualTo(1);
        assertThat(result.entries().items().getFirst().stage()).isNull();
    }

    @Test void unstartedAbandonmentIsDurableAndRejectsStaleDecisionsAndExecution() throws Exception {
        String id = review.plan().id();
        assertThat(query.get(id, 0, 50, true).review().canAbandon()).isTrue();
        assertThatThrownBy(() -> store.abandonUnstarted(id, 1)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        store.abandonUnstarted(id, 0);
        store.abandonUnstarted(id, 0);
        assertThat(store.run(id).terminal()).isTrue();
        assertThat(query.unresolved()).isEmpty();
        assertThat(query.get(id, 0, 50, true).review().canAbandon()).isFalse();
        assertThat(query.get(id, 0, 50, true).review().statusLabel()).isEqualTo("Copy abandoned");
        assertThatThrownBy(() -> store.freeze(id, 0)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        assertThatThrownBy(() -> store.chooseAll(id, 0, DirectoryTransferReview.Choice.SKIP))
                .isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        assertThat(Files.readString(root.resolve("source/photos/a.txt"))).isEqualTo("new");
        assertThat(Files.readString(root.resolve("destination/photos/a.txt"))).isEqualTo("old");
        var service = new DirectoryTransferService(store, org.mockito.Mockito.mock(DirectoryTransferExecution.class),
                org.mockito.Mockito.mock(DirectoryTransferFinalizer.class));
        assertThat(service.recover(id).phase()).isEqualTo(DirectoryTransferRun.Phase.ABANDONED);
    }

    @Test void frozenReviewCannotBeAbandonedEvenBeforeRunStarts() throws Exception {
        String id = review.plan().id();
        store.chooseAll(id, 0, DirectoryTransferReview.Choice.SKIP);
        store.freeze(id, 1);
        assertThatThrownBy(() -> store.abandonUnstarted(id, 1)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class);
        assertThat(query.get(id, 0, 50, true).review().canAbandon()).isFalse();
        assertThat(store.run(id)).isNull();
    }

    @Test void displayTargetUsesPublishedParentNameForUnpublishedChild() {
        var parent = review.plan().items().stream().filter(i -> i.relativePath().isEmpty()).findFirst().orElseThrow();
        var child = review.plan().items().stream().filter(i -> i.relativePath().equals("b.txt")).findFirst().orElseThrow();
        var result = new DirectoryTransferResult(parent.id(), DirectoryTransferResult.Status.PUBLISHED,
                "destination/photos - 1", parent.source(), null, null);
        assertThat(new DirectoryTransferIndex(review.plan()).displayTargetPath(child, Map.of(parent.id(), result)))
                .isEqualTo("destination/photos - 1/b.txt");
    }

    @Test void presentationCombinesOperationWithPhaseAndPauseWithoutNewRunStates() {
        for (var operation : DirectoryTransferPlan.Operation.values()) {
            for (var phase : DirectoryTransferRun.Phase.values()) {
                var active = new DirectoryTransferRun(review.plan().id(), 0, phase, false);
                var paused = new DirectoryTransferRun(review.plan().id(), 0, phase, true);
                assertThat(DirectoryTransferPresentation.status(operation, active, false)).isNotBlank();
                assertThat(DirectoryTransferPresentation.status(operation, paused, false))
                        .startsWith(DirectoryTransferPresentation.operation(operation) + " paused");
            }
        }
        assertThat(DirectoryTransferPresentation.status(DirectoryTransferPlan.Operation.MOVE,
                new DirectoryTransferRun(review.plan().id(), 0, DirectoryTransferRun.Phase.PUBLISHING, false), false)).isEqualTo("Moving");
    }

    @Test void unresolvedIncludesPausedWorkButExcludesCompletedReviews() throws Exception {
        String id = review.plan().id();
        assertThat(query.unresolved()).hasSize(1);
        var publishing = new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.PUBLISHING, true);
        store.saveRun(null, publishing);
        assertThat(query.unresolved().getFirst().run().paused()).isTrue();
        var finalizing = new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.FINALIZING, false);
        store.saveRun(publishing, finalizing);
        store.saveRun(finalizing, new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.COMPLETE, false));
        assertThat(query.unresolved()).isEmpty();
        assertThat(query.list(0, 10).total()).isEqualTo(1);
    }

    @Test void exposesRemappedDestinationWithoutExposingMutableInternalMapping() throws Exception {
        var plan = review.plan();
        var remapped = new DirectoryTransferPlan(java.util.UUID.randomUUID().toString(), plan.operation(),
                plan.sourceReference(), plan.destinationPath(), plan.createdAt(), plan.items(),
                Map.of("", "photos - 1", "a.txt", "a - 1.txt"));
        store.create(remapped);
        var detail = query.get(remapped.id(), 0, 10, true);
        assertThat(detail.entries().items().getFirst().plannedTargetPath()).isEqualTo("destination/photos - 1/a - 1.txt");
    }
}
