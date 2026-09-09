package io.github.fourilla.endervault.directorymerge;

import static org.assertj.core.api.Assertions.*;
import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryMergePlannerTest {
    @TempDir Path root;
    NasProperties properties;
    StorageService storage;
    DirectoryMergePlanner planner;

    @BeforeEach void setup() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storage.initialize();
        planner = new DirectoryMergePlanner(storage, properties);
        Files.createDirectories(root.resolve("source/photos"));
        Files.createDirectories(root.resolve("destination"));
    }

    private Path file(String relative, String text) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    private DirectoryMergePlan plan() throws IOException {
        return planner.planTransfer(Operation.COPY, "source/photos", "destination", null);
    }

    private Item item(DirectoryMergePlan plan, String path) {
        return plan.items().stream().filter(item -> item.relativePath().equals(path)).findFirst().orElseThrow();
    }

    private DirectoryMergeReviewStore store() {
        return new DirectoryMergeReviewStore(JsonMapper.builder().findAndAddModules().build(), properties);
    }

    @Test void scansNestedConflictsAndEmptyDirectoriesWithoutChangingFiles() throws Exception {
        file("source/photos/nested/same.txt", "new");
        file("source/photos/new.txt", "new only");
        Files.createDirectories(root.resolve("source/photos/empty"));
        file("destination/photos/nested/same.txt", "old");
        file("destination/photos/existing.txt", "keep");
        var result = plan();
        assertThat(item(result, "").conflict()).isEqualTo(Conflict.MERGE);
        assertThat(item(result, "nested").conflict()).isEqualTo(Conflict.MERGE);
        assertThat(item(result, "nested/same.txt").conflict()).isEqualTo(Conflict.FILE_CONFLICT);
        assertThat(item(result, "new.txt").conflict()).isEqualTo(Conflict.ADD);
        assertThat(item(result, "empty").source().kind()).isEqualTo(Kind.DIRECTORY);
        assertThat(result.items()).noneMatch(item -> item.relativePath().equals("existing.txt"));
        assertThat(root.resolve("destination/photos/new.txt")).doesNotExist();
        assertThat(root.resolve("destination/photos/nested/same.txt")).hasContent("old");
        assertThat(root.resolve("source/photos/nested/same.txt")).hasContent("new");
        assertThat(result.sourceReference()).isEqualTo("source/photos");
        assertThat(result.destinationPath()).isEqualTo("destination/photos");
    }

    @Test void newDestinationDoesNotGetCreatedByScan() throws Exception {
        file("source/photos/a/b/c.txt", "test");
        assertThat(plan().items()).allMatch(item -> item.conflict() == Conflict.ADD);
        assertThat(root.resolve("destination/photos")).doesNotExist();
    }

    @Test void directoryFileTypeConflictBlocksDescendantsInsteadOfPretendingTheyCanBeAdded() throws Exception {
        file("source/photos/nested/deeper/a.txt", "test");
        file("destination/photos/nested", "not a directory");
        var result = plan();
        Item parent = item(result, "nested");
        assertThat(parent.conflict()).isEqualTo(Conflict.TYPE_CONFLICT);
        assertThat(item(result, "nested/deeper/a.txt").conflict()).isEqualTo(Conflict.BLOCKED_BY_PARENT);
        assertThat(item(result, "nested/deeper/a.txt").blockedBy()).isEqualTo(parent.id());
        assertThat(item(result, "nested/deeper/a.txt").requiresDecision()).isFalse();
    }

    @Test void fileDirectoryConflictDoesNotInspectOrModifyDestinationOnlyTree() throws Exception {
        file("source/photos/nested", "file");
        file("destination/photos/nested/existing.txt", "keep");
        assertThat(item(plan(), "nested").conflict()).isEqualTo(Conflict.TYPE_CONFLICT);
        assertThat(root.resolve("destination/photos/nested/existing.txt")).hasContent("keep");
    }

    @Test void rejectsSelfDescendantAndAncestorTargetsAndVaultRoot() throws Exception {
        Files.createDirectories(root.resolve("source/photos/inside"));
        assertThatThrownBy(() -> planner.planTransfer(Operation.MOVE, "source/photos", "source", null))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> planner.planTransfer(Operation.COPY, "source/photos", "source/photos/inside", null))
                .isInstanceOf(StorageAccessException.class);
        Files.createDirectories(root.resolve("source/photos/inside/photos"));
        assertThatThrownBy(() -> planner.planTransfer(Operation.MOVE, "source/photos/inside/photos", "source", null))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> planner.planTransfer(Operation.MOVE, "", "destination", null))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test void checksCancellationDuringEnumerationAndDoesNotCreateDestination() throws Exception {
        file("source/photos/nested/a.txt", "test");
        var listener = new StorageProgressListener() {
            int processed;
            public void onItemProcessed() { processed++; }
            public void checkCanceled() { if (processed > 1) throw new IllegalStateException("Canceled"); }
        };
        assertThatThrownBy(() -> planner.planTransfer(Operation.COPY, "source/photos", "destination", listener))
                .isInstanceOf(IllegalStateException.class).hasMessage("Canceled");
        assertThat(root.resolve("destination/photos")).doesNotExist();
    }

    @Test void detectsObservableSourceChangeDuringScan() throws Exception {
        Path source = file("source/photos/a.txt", "old");
        var listener = new StorageProgressListener() {
            int processed;
            public void onItemProcessed() {
                if (++processed == 2) {
                    try { Files.writeString(source, "changed length"); }
                    catch (IOException ex) { throw new IllegalStateException(ex); }
                }
            }
        };
        assertThatThrownBy(() -> planner.planTransfer(Operation.COPY, "source/photos", "destination", listener))
                .isInstanceOf(StorageAccessException.class).hasMessageContaining("Source changed");
    }

    @Test void pendingPlanUsesServerOwnedStagingAndSameCollisionRules() throws Exception {
        Path staging = storage.resolveFileStagingFile("test-group");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("a.txt"), "new");
        file("destination/photos/a.txt", "old");
        String id = UUID.randomUUID().toString();
        var decision = new PendingFileDecision(id, PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "test-group", "destination", "photos", 3, Instant.now(), null, "group", "admin", true);
        var result = planner.planPending(decision, null);
        assertThat(result.operation()).isEqualTo(Operation.PENDING);
        assertThat(result.sourceReference()).isEqualTo(id);
        assertThat(item(result, "a.txt").conflict()).isEqualTo(Conflict.FILE_CONFLICT);
    }

    @Test void reviewSurvivesRestartWithoutAnyBrowserDismissalRequest() throws Exception {
        file("source/photos/a.txt", "new");
        file("destination/photos/a.txt", "old");
        var plan = plan();
        var store = store();
        assertThat(store.create(plan).fullyReviewed()).isFalse();
        var saved = store.choose(plan.id(), 0, Map.of(item(plan, "a.txt").id(), DirectoryMergeReview.Choice.OVERWRITE));
        assertThat(saved.fullyReviewed()).isTrue();
        assertThat(store().require(plan.id())).isEqualTo(saved);
        assertThat(store().ids()).containsExactly(plan.id());
        assertThat(root.resolve("destination/photos/a.txt")).hasContent("old");
    }

    @Test void staleSessionCannotOverwriteAnotherReviewAndUnknownIdsAreRejected() throws Exception {
        file("source/photos/a.txt", "new");
        file("destination/photos/a.txt", "old");
        var plan = plan();
        var store = store();
        store.create(plan);
        store.choose(plan.id(), 0, Map.of(item(plan, "a.txt").id(), DirectoryMergeReview.Choice.SKIP));
        assertThatThrownBy(() -> store.choose(plan.id(), 0, Map.of(item(plan, "a.txt").id(), DirectoryMergeReview.Choice.OVERWRITE)))
                .isInstanceOf(StorageAccessException.class).hasMessageContaining("changed");
        assertThatThrownBy(() -> store.choose(plan.id(), 1, Map.of("../../target", DirectoryMergeReview.Choice.OVERWRITE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.require(plan.id()).revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.require("../escape")).isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> store.create(plan)).isInstanceOf(StorageAccessException.class);
    }

    @Test void typeConflictsCannotBeOverwrittenAndUnrelatedEntriesCannotBeSelected() throws Exception {
        file("source/photos/nested/a.txt", "new");
        file("destination/photos/nested", "file");
        var plan = plan();
        assertThatThrownBy(() -> new DirectoryMergeReview(plan, 0,
                Map.of(item(plan, "nested").id(), DirectoryMergeReview.Choice.OVERWRITE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DirectoryMergeReview(plan, 0,
                Map.of(item(plan, "nested/a.txt").id(), DirectoryMergeReview.Choice.SKIP)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pendingSkipIsExplicitDiscardRatherThanSilentLoss() throws Exception {
        var source = new Snapshot(Kind.FILE, 3, Instant.now(), null, Instant.now());
        var item = new Item(UUID.randomUUID().toString(), "a.txt", source, source, Conflict.FILE_CONFLICT, null);
        var directory = new Snapshot(Kind.DIRECTORY, 0, Instant.now(), null, Instant.now());
        var parent = new Item(UUID.randomUUID().toString(), "", directory, directory, Conflict.MERGE, null);
        var plan = new DirectoryMergePlan(UUID.randomUUID().toString(), Operation.PENDING, "pending-id", "photos", Instant.now(), List.of(parent, item));
        assertThatThrownBy(() -> new DirectoryMergeReview(plan, 0, Map.of(item.id(), DirectoryMergeReview.Choice.SKIP)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DirectoryMergeReview(plan, 0, Map.of(item.id(), DirectoryMergeReview.Choice.DISCARD_UPLOAD)).fullyReviewed()).isTrue();
    }

    @Test void malformedButValidJsonDoesNotBecomeAnApprovedReview() throws Exception {
        file("source/photos/a.txt", "new");
        file("destination/photos/a.txt", "old");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        for (String defect : List.of("missing-revision", "missing-conflict", "wrong-type", "bad-path", "duplicate-path")) {
            var plan = plan();
            var store = store();
            var review = store.create(plan);
            ObjectNode node = (ObjectNode) mapper.valueToTree(review);
            ObjectNode first = (ObjectNode) node.path("plan").path("items").get(0);
            ObjectNode second = (ObjectNode) node.path("plan").path("items").get(1);
            switch (defect) {
                case "missing-revision" -> node.remove("revision");
                case "missing-conflict" -> first.remove("conflict");
                case "wrong-type" -> first.put("conflict", "FILE_CONFLICT");
                case "bad-path" -> second.put("relativePath", "../escape");
                case "duplicate-path" -> second.put("relativePath", "");
            }
            Path json = root.resolve(properties.getStorage().getMetadataDirectory())
                    .resolve("directory-merges").resolve(plan.id() + ".json");
            Files.writeString(json, mapper.writeValueAsString(node));
            assertThatThrownBy(() -> store.require(plan.id())).as(defect).isInstanceOf(IOException.class);
        }
    }

    @Test void symbolicAndDanglingLinksAreRejectedWithoutFollowingTheirContents() throws Exception {
        Path linked = root.resolve("source/photos/link");
        Path outside = Files.createDirectories(root.resolve("outside"));
        try { Files.createSymbolicLink(linked, outside); }
        catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + ex.getMessage());
        }
        assertThatThrownBy(this::plan).isInstanceOf(StorageAccessException.class);
        Files.delete(linked);
        Files.createSymbolicLink(linked, outside.resolve("missing"));
        assertThatThrownBy(this::plan).isInstanceOf(StorageAccessException.class);
        Files.delete(linked);

        file("source/photos/nested/a.txt", "new");
        Files.createDirectories(root.resolve("destination/photos"));
        Path targetLink = root.resolve("destination/photos/nested");
        Files.createSymbolicLink(targetLink, outside);
        assertThatThrownBy(this::plan).isInstanceOf(StorageAccessException.class);
        Files.delete(targetLink);

        Path ancestor = root.resolve("alias");
        Files.createSymbolicLink(ancestor, root.resolve("source"));
        assertThatThrownBy(() -> planner.planTransfer(Operation.COPY, "alias/photos", "destination", null))
                .isInstanceOf(StorageAccessException.class);
        Files.delete(ancestor);

        Files.createSymbolicLink(root.resolve("destination/photos/untouched"), outside);
        assertThat(plan().items()).noneMatch(item -> item.relativePath().equals("untouched"));
    }

    @Test void metadataSymlinkIsNotReadOrOverwritten() throws Exception {
        var store = store();
        var plan = plan();
        var saved = store.create(plan);
        Path json = root.resolve(properties.getStorage().getMetadataDirectory())
                .resolve("directory-merges").resolve(plan.id() + ".json");
        Path outside = file("untouched.json", "keep");
        Files.delete(json);
        try { Files.createSymbolicLink(json, outside); }
        catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + ex.getMessage());
        }
        assertThatThrownBy(() -> store.require(saved.plan().id())).isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> store.create(plan)).isInstanceOf(StorageAccessException.class);
        assertThat(outside).hasContent("keep");
    }

    @Test void reviewListingUsesSharedNaturalNameOrdering() throws Exception {
        file("source/photos/Q11.txt", "11");
        file("source/photos/Q2.txt", "2");
        assertThat(plan().items()).extracting(Item::relativePath).containsExactly("", "Q2.txt", "Q11.txt");
    }
}
