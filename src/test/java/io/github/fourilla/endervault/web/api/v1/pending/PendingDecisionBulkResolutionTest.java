package io.github.fourilla.endervault.web.api.v1.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.web.api.v1.pending.PendingFileDecisionApiController.BulkStatus;
import io.github.fourilla.endervault.web.api.v1.pending.PendingFileDecisionApiController.PendingFileDecisionBulkResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class PendingDecisionBulkResolutionTest {
    @TempDir Path root;
    private StorageService storage;
    private FileCommitJournalStore journals;
    private FileCommitCoordinator commits;
    private PendingFileDecisionService pending;
    private PendingFileDecisionApiController api;
    private final TemporaryArtifactRegistry artifacts = new TemporaryArtifactRegistry();
    private final PendingFileDecisionResolutionObserver observer = mock(PendingFileDecisionResolutionObserver.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void initialize() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        storage = new StorageService(properties, new FileActionRegistry(), artifacts);
        storage.initialize();
        journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        commits = new FileCommitCoordinator(journals, storage, properties);
        when(observer.supports(any())).thenReturn(true);
        var repository = new PendingFileDecisionRepository(mapper, properties);
        repository.initialize();
        pending = new PendingFileDecisionService(repository,
                storage, commits, artifacts, List.of(observer));
        pending.restoreRegistrations();
        api = new PendingFileDecisionApiController(pending, mock(ActivityLogService.class), mock(DirectoryTransferQueryService.class));
    }

    @Test
    void keepBothCommitsFileAndDirectoryUsingExistingNamingJournalAndObservers() throws Exception {
        Files.writeString(root.resolve("photo.txt"), "old file");
        Files.createDirectory(root.resolve("album"));
        Files.writeString(root.resolve("album/old.txt"), "old directory");
        var file = stage("photo.txt", false);
        var directory = stage("album", true);
        var result = resolve(List.of(file.id(), directory.id()), PendingFileDecisionAction.KEEP_BOTH);
        assertThat(result.succeededCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(root.resolve("photo.txt")).hasContent("old file");
        assertThat(root.resolve("photo - 1.txt")).hasContent("new data");
        assertThat(root.resolve("album/old.txt")).hasContent("old directory");
        assertThat(root.resolve("album - 1/nested/data.txt")).hasContent("new data");
        assertThat(root.resolve("album - 1/nested/empty")).isEmptyDirectory();
        assertThat(pending.list()).isEmpty();
        assertThat(journals.list()).isEmpty();
        assertThat(artifacts.isActive(storage.resolveFileStagingFile(file.stagingFilename()))).isFalse();
        assertThat(artifacts.isActive(storage.resolveFileStagingFile(directory.stagingFilename()))).isFalse();
        verify(observer, times(2)).afterResolved(any(), eq(PendingFileDecisionAction.KEEP_BOTH), eq(false), any());
    }

    @Test
    void replaceRevalidatesTargetsAndRejectsDirectoriesWithoutBlockingUnchangedFiles() throws Exception {
        Files.writeString(root.resolve("changed.txt"), "old");
        Files.writeString(root.resolve("unchanged.txt"), "old");
        Files.createDirectory(root.resolve("folder"));
        var changed = stage("changed.txt", false);
        var directory = stage("folder", true);
        var unchanged = stage("unchanged.txt", false);
        Files.writeString(root.resolve("changed.txt"), "externally modified and longer");
        var result = resolve(List.of(changed.id(), directory.id(), unchanged.id()), PendingFileDecisionAction.REPLACE);
        assertThat(result.results()).extracting(item -> item.status())
                .containsExactly(BulkStatus.REJECTED, BulkStatus.REJECTED, BulkStatus.RESOLVED);
        assertThat(root.resolve("changed.txt")).hasContent("externally modified and longer");
        assertThat(root.resolve("folder")).isEmptyDirectory();
        assertThat(root.resolve("unchanged.txt")).hasContent("new data");
        assertThat(storage.resolveFileStagingFile(changed.stagingFilename())).hasContent("new data");
        assertThat(storage.resolveFileStagingFile(directory.stagingFilename()).resolve("nested/data.txt")).hasContent("new data");
        assertThat(pending.list()).extracting(item -> item.id()).containsExactlyInAnyOrder(changed.id(), directory.id());
        assertThat(journals.list()).isEmpty();
        verify(observer, times(1)).afterResolved(eq(unchanged), eq(PendingFileDecisionAction.REPLACE), eq(false), any());
    }

    @Test
    void discardProtectsMergeClaimsAndAnAlreadyPublishedDirectoryJournal() throws Exception {
        var owned = stage("owned", true);
        var journalled = stage("journalled", true);
        var file = stage("file.txt", false);
        var directory = stage("ordinary-folder", true);
        String owner = UUID.randomUUID().toString();
        pending.claimDirectoryMerge(owned.id(), owner);
        var publication = commits.commitSingleDirectory(new FileCommitOwner(FileCommitOwnerType.PENDING_FILE_DECISION, journalled.id()),
                storage.resolveFileStagingFile(journalled.stagingFilename()), "", "journalled", ConflictPolicy.CANCEL);
        var result = resolve(List.of(owned.id(), journalled.id(), file.id(), directory.id()), PendingFileDecisionAction.DISCARD);
        assertThat(result.results()).extracting(item -> item.status())
                .containsExactly(BulkStatus.REJECTED, BulkStatus.REJECTED, BulkStatus.RESOLVED, BulkStatus.RESOLVED);
        assertThat(pending.requireDirectoryMergeOwner(owned.id(), owner)).isEqualTo(owned);
        assertThat(storage.resolveFileStagingFile(owned.stagingFilename()).resolve("nested/data.txt")).hasContent("new data");
        assertThat(root.resolve("journalled/nested/data.txt")).hasContent("new data");
        assertThat(journals.load(publication.operationId())).isNotNull();
        assertThat(storage.resolveFileStagingFile(file.stagingFilename())).doesNotExist();
        assertThat(storage.resolveFileStagingFile(directory.stagingFilename())).doesNotExist();
        assertThat(pending.list()).extracting(item -> item.id()).containsExactlyInAnyOrder(owned.id(), journalled.id());
        verify(observer, times(2)).afterResolved(any(), eq(PendingFileDecisionAction.DISCARD), eq(true), isNull());
    }

    @Test
    void removedStagingAndRepeatedRequestsNeverRepublishAlreadyResolvedItems() throws Exception {
        Files.writeString(root.resolve("kept.txt"), "old");
        var missing = stage("missing.txt", false);
        var kept = stage("kept.txt", false);
        Files.delete(storage.resolveFileStagingFile(missing.stagingFilename()));
        var ids = List.of(missing.id(), kept.id());
        var first = resolve(ids, PendingFileDecisionAction.KEEP_BOTH);
        assertThat(first.results()).extracting(item -> item.status()).containsExactly(BulkStatus.NOT_FOUND, BulkStatus.RESOLVED);
        assertThat(pending.list()).isEmpty();
        var repeated = resolve(ids, PendingFileDecisionAction.KEEP_BOTH);
        assertThat(repeated.results()).extracting(item -> item.status()).containsOnly(BulkStatus.NOT_FOUND);
        assertThat(root.resolve("kept - 1.txt")).hasContent("new data");
        assertThat(root.resolve("kept - 2.txt")).doesNotExist();
        assertThat(root.resolve("kept.txt")).hasContent("old");
        assertThat(journals.list()).isEmpty();
        verify(observer, times(1)).afterResolved(eq(kept), eq(PendingFileDecisionAction.KEEP_BOTH), eq(false), any());
    }

    @Test
    void eachItemChecksOwnershipAgainEvenWhenItChangesDuringTheBatch() throws Exception {
        var first = stage("first.txt", false);
        var next = stage("next-folder", true);
        String owner = UUID.randomUUID().toString();
        doAnswer(call -> {
            pending.claimDirectoryMerge(next.id(), owner);
            return null;
        }).when(observer).afterResolved(eq(first), eq(PendingFileDecisionAction.DISCARD), eq(true), isNull());
        var result = resolve(List.of(first.id(), next.id()), PendingFileDecisionAction.DISCARD);
        assertThat(result.results()).extracting(item -> item.status()).containsExactly(BulkStatus.RESOLVED, BulkStatus.REJECTED);
        assertThat(pending.requireDirectoryMergeOwner(next.id(), owner)).isEqualTo(next);
        assertThat(storage.resolveFileStagingFile(next.stagingFilename()).resolve("nested/data.txt")).hasContent("new data");
    }

    @Test
    void competingBatchesResolveEachIdAtMostOnceWithoutDeadlockingOrRepublishing() throws Exception {
        Files.writeString(root.resolve("a.txt"), "old a");
        Files.writeString(root.resolve("b.txt"), "old b");
        var a = stage("a.txt", false);
        var b = stage("b.txt", false);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return resolve(List.of(a.id(), b.id()), PendingFileDecisionAction.KEEP_BOTH); });
            var second = executor.submit(() -> { start.await(); return resolve(List.of(b.id(), a.id()), PendingFileDecisionAction.KEEP_BOTH); });
            start.countDown();
            var combined = Stream.concat(first.get(20, TimeUnit.SECONDS).results().stream(), second.get(20, TimeUnit.SECONDS).results().stream()).toList();
            assertThat(combined.stream().filter(item -> item.status() == BulkStatus.RESOLVED)).hasSize(2);
            assertThat(combined.stream().filter(item -> item.status() == BulkStatus.NOT_FOUND)).hasSize(2);
        }
        assertThat(root.resolve("a - 1.txt")).hasContent("new data");
        assertThat(root.resolve("b - 1.txt")).hasContent("new data");
        assertThat(root.resolve("a - 2.txt")).doesNotExist();
        assertThat(root.resolve("b - 2.txt")).doesNotExist();
        assertThat(pending.list()).isEmpty();
        assertThat(journals.list()).isEmpty();
        verify(observer, times(2)).afterResolved(any(), eq(PendingFileDecisionAction.KEEP_BOTH), eq(false), any());
    }

    private PendingFileDecision stage(String name, boolean directory) throws Exception {
        Path staged;
        if (directory) {
            staged = Files.createDirectory(storage.resolveFileStagingFile("bulk-directory-" + UUID.randomUUID()));
            Files.createDirectories(staged.resolve("nested/empty"));
            Files.writeString(staged.resolve("nested/data.txt"), "new data");
        } else {
            staged = storage.createFileStagingTemporaryFile("bulk-", ".tmp");
            Files.writeString(staged, "new data");
        }
        return pending.create(staged, directory ? PendingFileDecisionSource.DIRECTORY_UPLOAD : PendingFileDecisionSource.ADMIN_UPLOAD,
                "", name, 8);
    }

    private PendingFileDecisionBulkResponse resolve(List<String> ids, PendingFileDecisionAction action) {
        var response = api.resolveSelected(ids, action, action == PendingFileDecisionAction.REPLACE, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (PendingFileDecisionBulkResponse) response.getBody();
    }
}
