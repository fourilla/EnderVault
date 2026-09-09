package io.github.fourilla.endervault.directorymerge;

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

class DirectoryMergeQueryServiceTest {
    @TempDir Path root;
    DirectoryMergeReviewStore store;
    DirectoryMergeQueryService query;
    DirectoryMergeReview review;

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
        store = new DirectoryMergeReviewStore(JsonMapper.builder().findAndAddModules().build(), properties);
        review = store.create(new DirectoryMergePlanner(storage, properties)
                .planTransfer(DirectoryMergePlan.Operation.COPY, "source/photos", "destination", null));
        query = new DirectoryMergeQueryService(store);
    }

    @Test void durableListingFindsReviewWithoutAnyTaskHistory() throws Exception {
        var page = new DirectoryMergeQueryService(store).list(0, 10);
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
        store.choose(review.plan().id(), 0, Map.of(conflict, DirectoryMergeReview.Choice.SKIP));
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

    @Test void unresolvedIncludesPausedWorkButExcludesCompletedReviews() throws Exception {
        String id = review.plan().id();
        assertThat(query.unresolved()).hasSize(1);
        var publishing = new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.PUBLISHING, true);
        store.saveRun(null, publishing);
        assertThat(query.unresolved().getFirst().run().paused()).isTrue();
        var finalizing = new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.FINALIZING, false);
        store.saveRun(publishing, finalizing);
        store.saveRun(finalizing, new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.COMPLETE, false));
        assertThat(query.unresolved()).isEmpty();
        assertThat(query.list(0, 10).total()).isEqualTo(1);
    }

    @Test void exposesRemappedDestinationWithoutExposingMutableInternalMapping() throws Exception {
        var plan = review.plan();
        var remapped = new DirectoryMergePlan(java.util.UUID.randomUUID().toString(), plan.operation(),
                plan.sourceReference(), plan.destinationPath(), plan.createdAt(), plan.items(),
                Map.of("", "photos - 1", "a.txt", "a - 1.txt"));
        store.create(remapped);
        var detail = query.get(remapped.id(), 0, 10, true);
        assertThat(detail.entries().items().getFirst().plannedTargetPath()).isEqualTo("destination/photos - 1/a - 1.txt");
    }
}
