package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.metadata.MetadataIssueAction;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferMetadataInspectorTest {
    @TempDir Path vault;
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    DirectoryTransferReviewStore store;
    DirectoryTransferMetadataInspector inspector;
    DirectoryTransferReview review;
    final io.github.fourilla.endervault.filecommit.FileCommitJournalStore journals =
            mock(io.github.fourilla.endervault.filecommit.FileCommitJournalStore.class);
    Path root;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(vault);
        var storage = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storage.initialize();
        Files.createDirectories(vault.resolve("source/photos"));
        Files.createDirectories(vault.resolve("target"));
        store = new DirectoryTransferReviewStore(mapper, properties);
        root = store.inspectionRoot();
        inspector = new DirectoryTransferMetadataInspector(store, mapper, journals, mock(DirectoryTransferPendingInspector.class));
        assertThat(inspector.inspect()).isEmpty();
        assertThat(root).doesNotExist();
        review = store.create(new DirectoryTransferPlanner(storage, properties)
                .planTransfer(DirectoryTransferPlan.Operation.COPY, "source/photos", "target", null));
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
        write("runs/" + review.plan().id() + ".json", new DirectoryTransferRun(review.plan().id(), 0,
                DirectoryTransferRun.Phase.PUBLISHING, true));
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void abandonedReviewMayHaveCopyApprovalButCannotRemainPaused() throws Exception {
        String id = review.plan().id();
        store.abandonUnstarted(id, 0);
        assertThat(inspector.inspect()).isEmpty();
        write("executions/" + id + ".json", review);
        assertThat(inspector.inspect()).isEmpty();
        write("runs/" + id + ".json", new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.ABANDONED, true));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("Abandoned unstarted"));
    }

    @Test void completeRequiresAllItemRecordsButHealthyCompletionIsNotAnIssue() throws Exception {
        String id = review.plan().id();
        var item = review.plan().items().getFirst();
        store.freeze(id, 0);
        write("runs/" + id + ".json", new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.COMPLETE, false));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("missing per-item"));
        write("results/" + id + "/" + item.id() + ".json", new DirectoryTransferResult(item.id(),
                DirectoryTransferResult.Status.PUBLISHED, "target/photos", item.source(), null, null));
        write("completion/" + id + "/" + item.id() + ".json", new DirectoryTransferCompletion(item.id(),
                DirectoryTransferCompletion.Phase.COMPLETE, null));
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
        write("runs/" + orphan + ".json", new DirectoryTransferRun(orphan, 0, DirectoryTransferRun.Phase.PUBLISHING, false));
        store.freeze(review.plan().id(), 0);
        String unknown = UUID.randomUUID().toString();
        String relative = "completion/" + review.plan().id() + "/" + unknown + ".json";
        write(relative, new DirectoryTransferCompletion(unknown, DirectoryTransferCompletion.Phase.COMPLETE, null));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("without their merge review"))
                .anyMatch(issue -> issue.title().contains("no matching approved plan item"));
        assertThat(root.resolve(relative)).exists();
        assertThat(inspector.inspect()).allMatch(issue -> !issue.repairable());
        assertThatThrownBy(() -> inspector.repair(MetadataIssueAction.NONE, orphan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void mismatchedApprovalAndMissingSuccessorAreReported() throws Exception {
        String id = review.plan().id();
        write("executions/" + id + ".json", new DirectoryTransferReview(review.plan(), 1, Map.of()));
        write("runs/" + id + ".json", new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false));
        write("successors/" + id + ".json", Map.of("previous", id, "next", UUID.randomUUID().toString()));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("approval does not match"))
                .anyMatch(issue -> issue.title().contains("unreadable"));
    }

    @Test void cyclicSuccessorAndUnexpectedEntriesAreReported() throws Exception {
        String id = review.plan().id();
        store.freeze(id, 0);
        write("runs/" + id + ".json", new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false));
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

    FileCommitJournalInspection journal(String owner, String target, ConflictPolicy policy) {
        String operationId = UUID.randomUUID().toString();
        var entry = mock(FileCommitJournalEntry.class);
        var manifest = mock(FileCommitManifest.class);
        when(entry.manifest()).thenReturn(manifest);
        when(manifest.owner()).thenReturn(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE, owner));
        when(manifest.operationId()).thenReturn(operationId);
        when(manifest.operationType()).thenReturn(FileCommitOperationType.SINGLE_DIRECTORY);
        when(manifest.conflictPolicy()).thenReturn(policy);
        var item = mock(FileCommitItem.class);
        var fingerprint = mock(FileCommitFingerprint.class);
        when(fingerprint.directory()).thenReturn(true);
        when(item.stagingFingerprint()).thenReturn(fingerprint);
        when(item.targetPath()).thenReturn(target);
        when(manifest.items()).thenReturn(List.of(item));
        return new FileCommitJournalInspection(operationId, entry, java.time.Instant.now(), null);
    }

    String owner() { return review.plan().id() + ":" + review.plan().items().getFirst().id(); }

    void setJournals(FileCommitJournalInspection... entries) throws Exception {
        when(journals.inspectJournals()).thenReturn(List.of(entries));
    }

    @Test void validPreparedJournalNeedsNoResultYetAndIsNeverMutated() throws Exception {
        store.freeze(review.plan().id(), 0);
        setJournals(journal(owner(), "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).isEmpty();
        verify(journals, never()).deleteFinished(anyString());
        verify(journals, never()).updateState(any());
    }

    @Test void journalTargetAndPolicyMustMatchTheReview() throws Exception {
        store.freeze(review.plan().id(), 0);
        setJournals(journal(owner(), "elsewhere/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("does not match"));
        setJournals(journal(owner(), "target/photos", ConflictPolicy.OVERWRITE));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("does not match"));
    }

    @Test void journalWithoutFrozenApprovalAndMalformedOwnerAreReported() throws Exception {
        setJournals(journal(owner(), "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("no readable matching approval"));
        setJournals(journal("../../bad", "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("invalid directory merge owner"));
    }

    @Test void duplicateOwnersAndUnknownItemsAreReported() throws Exception {
        store.freeze(review.plan().id(), 0);
        setJournals(journal(owner(), "target/photos", ConflictPolicy.CANCEL),
                journal(owner(), "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("does not match"));
        setJournals(journal(review.plan().id() + ":" + UUID.randomUUID(), "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("does not match"));
    }

    @Test void resultCannotPointToAnotherExistingJournal() throws Exception {
        String id = review.plan().id();
        var item = review.plan().items().getFirst();
        store.freeze(id, 0);
        write("results/" + id + "/" + item.id() + ".json", new DirectoryTransferResult(item.id(),
                DirectoryTransferResult.Status.PUBLISHED, "target/photos", item.source(), UUID.randomUUID().toString(), null));
        setJournals(journal(owner(), "target/photos", ConflictPolicy.CANCEL));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("does not match"));
    }
}
