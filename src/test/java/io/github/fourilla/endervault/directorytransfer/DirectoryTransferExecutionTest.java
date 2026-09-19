package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferExecutionTest {
    @TempDir Path root;
    NasProperties properties;
    StorageService storage;
    DirectoryTransferReviewStore reviews;
    FileCommitCoordinator commits;
    DirectoryTransferExecution execution;
    TemporaryArtifactRegistry registry;

    @BeforeEach void setup() throws IOException {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
        reviews = spy(new DirectoryTransferReviewStore(JsonMapper.builder().findAndAddModules().build(), properties));
        var journals = new FileCommitJournalStore(JsonMapper.builder().findAndAddModules().build(), properties);
        journals.initialize();
        commits = spy(new FileCommitCoordinator(journals, storage, properties));
        registry = new TemporaryArtifactRegistry();
        restart();
        Files.createDirectories(root.resolve("from/photos"));
        Files.createDirectory(root.resolve("to"));
    }

    void restart() {
        execution = new DirectoryTransferExecution(reviews, new DirectoryTransferFilePublisher(reviews, storage, commits, registry),
                commits, storage, registry);
    }

    Path file(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    DirectoryTransferReview plan(Map<String, DirectoryTransferReview.Choice> choices) throws IOException {
        var plan = new DirectoryTransferPlanner(storage, properties).planTransfer(Operation.COPY, "from/photos", "to", null);
        var review = reviews.create(plan);
        if (choices.isEmpty()) return review;
        var byId = new HashMap<String, DirectoryTransferReview.Choice>();
        for (var item : plan.items()) if (choices.containsKey(item.relativePath())) byId.put(item.id(), choices.get(item.relativePath()));
        return reviews.choose(plan.id(), 0, byId);
    }

    Map<String, DirectoryTransferResult> run(DirectoryTransferReview review) throws IOException {
        return execution.publishTransfer(review.plan().id(), review.revision(), null);
    }

    DirectoryTransferResult result(DirectoryTransferReview review, Map<String, DirectoryTransferResult> results, String relative) {
        return results.get(review.plan().items().stream().filter(item -> item.relativePath().equals(relative)).findFirst().orElseThrow().id());
    }

    @Test void createsNestedDirectoriesAndEmptyDirectoriesThenReplaysWithoutDuplicates() throws Exception {
        file("from/photos/nested/a.txt", "a");
        Files.createDirectories(root.resolve("from/photos/empty/deeper"));
        var review = plan(Map.of());
        var results = run(review);
        assertThat(results.values()).allMatch(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED);
        assertThat(root.resolve("to/photos/nested/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos/empty/deeper")).isDirectory();
        assertThat(root.resolve("from/photos/nested/a.txt")).hasContent("a");
        restart();
        assertThat(run(review)).isEqualTo(results);
    }

    @Test void rootTypeConflictKeepBothRemapsTheWholeTree() throws Exception {
        file("from/photos/nested/a.txt", "a");
        file("to/photos", "existing file");
        var review = plan(Map.of("", DirectoryTransferReview.Choice.KEEP_BOTH));
        var results = run(review);
        String target = result(review, results, "").targetPath();
        assertThat(target).isNotEqualTo("to/photos");
        assertThat(root.resolve(target).resolve("nested/a.txt")).hasContent("a");
        assertThat(root.resolve("to/photos")).hasContent("existing file");
        restart();
        assertThat(run(review)).isEqualTo(results);
    }

    @Test void skipDirectoryPreservesSubtreeAndPublishesUnrelatedSiblings() throws Exception {
        file("from/photos/blocked/a.txt", "keep original");
        file("from/photos/ok.txt", "ok");
        file("to/photos/blocked", "target file");
        var review = plan(Map.of("blocked", DirectoryTransferReview.Choice.SKIP));
        var results = run(review);
        assertThat(result(review, results, "blocked/a.txt").status()).isEqualTo(DirectoryTransferResult.Status.SKIPPED);
        assertThat(root.resolve("from/photos/blocked/a.txt")).hasContent("keep original");
        assertThat(root.resolve("to/photos/blocked")).hasContent("target file");
        assertThat(root.resolve("to/photos/ok.txt")).hasContent("ok");
    }

    @Test void changedFileGoesToReviewWithoutBlockingUnrelatedFile() throws Exception {
        file("from/photos/a.txt", "a");
        file("from/photos/b.txt", "b");
        Files.createDirectory(root.resolve("to/photos"));
        var review = plan(Map.of());
        file("to/photos/a.txt", "new external file");
        var results = run(review);
        assertThat(result(review, results, "a.txt").status()).isEqualTo(DirectoryTransferResult.Status.NEEDS_REVIEW);
        assertThat(result(review, results, "b.txt").status()).isEqualTo(DirectoryTransferResult.Status.PUBLISHED);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("new external file");
        assertThat(root.resolve("to/photos/b.txt")).hasContent("b");
    }

    @Test void resumesDirectoryPublicationAfterResultWriteFailure() throws Exception {
        file("from/photos/a.txt", "a");
        var review = plan(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (!once.getAndSet(true)) throw new IOException("interrupted before result write");
            return call.callRealMethod();
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> run(review)).isInstanceOf(IOException.class);
        assertThat(root.resolve("to/photos")).isDirectory();
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
        restart();
        assertThat(run(review).values()).allMatch(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("a");
    }

    @Test void resultIsDurableBeforeDirectoryJournalCompletion() throws Exception {
        file("from/photos/a.txt", "a");
        var review = plan(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (!once.getAndSet(true)) throw new IOException("interrupted before journal cleanup");
            return call.callRealMethod();
        }).when(commits).complete(anyString());
        assertThatThrownBy(() -> run(review)).isInstanceOf(IOException.class);
        assertThat(reviews.results(review)).hasSize(1);
        restart();
        assertThat(run(review).values()).allMatch(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED);
    }

    @Test void cancellationAfterOneItemRetainsProgressAndCanResume() throws Exception {
        file("from/photos/a.txt", "a");
        var review = plan(Map.of());
        assertThatThrownBy(() -> execution.publishTransfer(review.plan().id(), 0, new StorageProgressListener() {
            @Override public void onItemProcessed() { throw new IllegalStateException("cancelled"); }
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reviews.results(review)).hasSize(1);
        restart();
        assertThat(run(review).values()).allMatch(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED);
    }

    @Test void pendingDirectoryPublishesTreeButRetainsOriginalUpload() throws Exception {
        Path staged = storage.resolveFileStagingFile("pending-tree");
        Files.createDirectories(staged.resolve("nested"));
        Files.writeString(staged.resolve("nested/a.txt"), "a");
        var pending = new PendingFileDecision(UUID.randomUUID().toString(), PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "pending-tree", "to", "photos", 1, Instant.now(), null, "group", "admin", true);
        var plan = new DirectoryTransferPlanner(storage, properties).planPending(pending, null);
        reviews.create(plan);
        var results = execution.publishPending(plan.id(), 0, pending, null);
        assertThat(results.values()).allMatch(r -> r.status() == DirectoryTransferResult.Status.PUBLISHED);
        assertThat(root.resolve("to/photos/nested/a.txt")).hasContent("a");
        assertThat(staged.resolve("nested/a.txt")).hasContent("a");
    }

    @Test void corruptedResultTargetIsRejectedBeforeFurtherPublication() throws Exception {
        file("from/photos/a.txt", "a");
        var review = plan(Map.of());
        var results = run(review);
        var first = result(review, results, "");
        Path resultFile = root.resolve(".endervault/directory-merges/results")
                .resolve(review.plan().id()).resolve(first.itemId() + ".json");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(resultFile));
        tree.put("targetPath", "another/path");
        Files.writeString(resultFile, mapper.writeValueAsString(tree));
        assertThatThrownBy(() -> run(review)).isInstanceOf(io.github.fourilla.endervault.common.StorageAccessException.class)
                .hasMessageContaining("target does not match");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishedFileIsReconciledAfterLostResultEvenIfOriginalChanged(boolean replaceParent) throws Exception {
        file("from/photos/a.txt", "original");
        var review = plan(Map.of());
        var once = new AtomicBoolean();
        doAnswer(call -> {
            Item item = call.getArgument(1);
            if (item.source().kind() == Kind.FILE && !once.getAndSet(true)) throw new IOException("lost file result");
            return call.callRealMethod();
        }).when(reviews).recordResult(any(), any(), any());
        assertThatThrownBy(() -> run(review)).isInstanceOf(IOException.class);
        if (replaceParent) {
            Files.move(root.resolve("from/photos"), root.resolve("from/previous-photos"));
            Files.createDirectory(root.resolve("from/photos"));
        }
        file("from/photos/a.txt", "new external source");
        restart();
        var results = run(review);
        assertThat(result(review, results, "a.txt").status()).isEqualTo(DirectoryTransferResult.Status.PUBLISHED);
        assertThat(root.resolve("to/photos/a.txt")).hasContent("original");
        assertThat(root.resolve("from/photos/a.txt")).hasContent("new external source");
    }

    @Test void corruptJournalCannotPublishOutsideReviewedMapping() throws Exception {
        file("from/photos/a.txt", "a");
        Files.createDirectory(root.resolve("to/photos"));
        var review = plan(Map.of());
        doThrow(new IOException("interrupted before publish")).when(commits).resumeSingleFile(anyString());
        assertThatThrownBy(() -> run(review)).isInstanceOf(IOException.class);
        var entry = commits.inspectJournals().stream().filter(i -> i.entry().manifest().operationType() == FileCommitOperationType.SINGLE_FILE)
                .findFirst().orElseThrow().entry();
        Path manifest = root.resolve(".endervault/commit-journal").resolve(entry.manifest().operationId()).resolve("manifest.json");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(manifest));
        ((tools.jackson.databind.node.ObjectNode) tree.get("items").get(0)).put("targetPath", "to/unreviewed.txt");
        Files.writeString(manifest, mapper.writeValueAsString(tree));
        doCallRealMethod().when(commits).resumeSingleFile(anyString());
        restart();
        var results = run(review);
        assertThat(result(review, results, "a.txt").status()).isEqualTo(DirectoryTransferResult.Status.NEEDS_REVIEW);
        assertThat(root.resolve("to/unreviewed.txt")).doesNotExist();
        assertThat(root.resolve("to/photos/a.txt")).doesNotExist();
    }
}
