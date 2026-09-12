package io.github.fourilla.endervault.directorymerge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.metadata.MetadataIssueAction;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryMergeMetadataInspectorTest {
    @TempDir Path vault;
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    DirectoryMergeReviewStore store;
    DirectoryMergeMetadataInspector inspector;
    DirectoryMergeReview review;
    Path root;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(vault);
        var storage = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storage.initialize();
        Files.createDirectories(vault.resolve("source/photos"));
        Files.createDirectories(vault.resolve("target"));
        store = new DirectoryMergeReviewStore(mapper, properties);
        root = store.inspectionRoot();
        inspector = new DirectoryMergeMetadataInspector(store, mapper);
        assertThat(inspector.inspect()).isEmpty();
        assertThat(root).doesNotExist();
        review = store.create(new DirectoryMergePlanner(storage, properties)
                .planTransfer(DirectoryMergePlan.Operation.COPY, "source/photos", "target", null));
    }

    void write(String relative, Object value) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, mapper.writeValueAsBytes(value));
    }

    @Test void ordinaryReviewFrozenWindowAndPausedRunAreNotIssues() throws Exception {
        assertThat(inspector.inspect()).isEmpty();
        store.freeze(review.plan().id(), 0);
        assertThat(inspector.inspect()).isEmpty();
        write("runs/" + review.plan().id() + ".json", new DirectoryMergeRun(review.plan().id(), 0,
                DirectoryMergeRun.Phase.PUBLISHING, true));
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void completeRequiresAllItemRecordsButHealthyCompletionIsNotAnIssue() throws Exception {
        String id = review.plan().id();
        var item = review.plan().items().getFirst();
        store.freeze(id, 0);
        write("runs/" + id + ".json", new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.COMPLETE, false));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("missing per-item"));
        write("results/" + id + "/" + item.id() + ".json", new DirectoryMergeResult(item.id(),
                DirectoryMergeResult.Status.PUBLISHED, "target/photos", item.source(), null, null));
        write("completion/" + id + "/" + item.id() + ".json", new DirectoryMergeCompletion(item.id(),
                DirectoryMergeCompletion.Phase.COMPLETE, null));
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void malformedReviewIsReportedWithoutBackupRenameOrDeletion() throws Exception {
        Path path = root.resolve(review.plan().id() + ".json");
        Files.writeString(path, "{broken");
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("unreadable"));
        assertThat(Files.readString(path)).isEqualTo("{broken");
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(1); }
    }

    @Test void orphanAndUnknownItemRecordsAreNotDeleted() throws Exception {
        String orphan = UUID.randomUUID().toString();
        write("runs/" + orphan + ".json", new DirectoryMergeRun(orphan, 0, DirectoryMergeRun.Phase.PUBLISHING, false));
        store.freeze(review.plan().id(), 0);
        String unknown = UUID.randomUUID().toString();
        String relative = "completion/" + review.plan().id() + "/" + unknown + ".json";
        write(relative, new DirectoryMergeCompletion(unknown, DirectoryMergeCompletion.Phase.COMPLETE, null));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("without their merge review"))
                .anyMatch(issue -> issue.title().contains("no matching approved plan item"));
        assertThat(root.resolve(relative)).exists();
        assertThat(inspector.inspect()).allMatch(issue -> !issue.repairable());
        assertThatThrownBy(() -> inspector.repair(MetadataIssueAction.NONE, orphan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void mismatchedApprovalAndMissingSuccessorAreReported() throws Exception {
        String id = review.plan().id();
        write("executions/" + id + ".json", new DirectoryMergeReview(review.plan(), 1, Map.of()));
        write("runs/" + id + ".json", new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.NEEDS_REVIEW, false));
        write("successors/" + id + ".json", Map.of("previous", id, "next", UUID.randomUUID().toString()));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("approval does not match"))
                .anyMatch(issue -> issue.title().contains("unreadable"));
    }

    @Test void cyclicSuccessorAndUnexpectedEntriesAreReported() throws Exception {
        String id = review.plan().id();
        store.freeze(id, 0);
        write("runs/" + id + ".json", new DirectoryMergeRun(id, 0, DirectoryMergeRun.Phase.NEEDS_REVIEW, false));
        write("successors/" + id + ".json", Map.of("previous", id, "next", id));
        Files.writeString(root.resolve("unexpected.tmp"), "preserve");
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("cyclic"))
                .anyMatch(issue -> issue.title().contains("Unexpected"));
    }

    @Test void cancellationIsNotConvertedIntoCorruption() {
        var context = mock(TaskContext.class);
        doThrow(new TaskCanceledException()).when(context).checkCanceled();
        assertThatThrownBy(() -> inspector.inspect(context)).isInstanceOf(TaskCanceledException.class);
    }
}
