package io.github.fourilla.endervault.upload;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class DirectoryUploadServiceTest {
    @TempDir Path root;
    private NasProperties properties;
    private StorageService storage;
    private DirectoryUploadRepository repository;
    private ResumableUploadService uploads;
    private PendingFileDecisionService pending;
    private FileCommitCoordinator commits;
    private FileCommitJournalStore journals;
    private TemporaryArtifactRegistry artifacts;
    private DirectoryUploadService service;

    @BeforeEach void setup() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        artifacts = new TemporaryArtifactRegistry();
        storage = new StorageService(properties, new io.github.fourilla.endervault.filetool.FileActionRegistry(), artifacts);
        storage.initialize();
        journals = new FileCommitJournalStore(mapper, properties);
        journals.initialize();
        commits = new FileCommitCoordinator(journals, storage, properties);
        var decisions = new PendingFileDecisionRepository(mapper, properties);
        decisions.initialize();
        pending = new PendingFileDecisionService(decisions, storage, commits, artifacts, List.of());
        var sessions = new ResumableUploadRepository(mapper, properties);
        sessions.initialize();
        uploads = new ResumableUploadService(sessions, mock(FileRequestService.class), storage, pending, commits, properties);
        repository = new DirectoryUploadRepository(mapper, properties);
        service = service();
    }

    private DirectoryUploadService service() {
        return new DirectoryUploadService(repository, uploads, mock(ResumableUploadCoordinator.class), storage,
                commits, pending, artifacts, properties, mock(ActivityLogService.class));
    }
    private DirectoryUploadManifest manifest(String name) {
        return new DirectoryUploadManifest(name, List.of(new DirectoryUploadManifest.File("nested/a.txt", 3, 1),
                new DirectoryUploadManifest.File("b.txt", 3, 2)), List.of("empty"), null);
    }
    private String receive(DirectoryUpload group, String path, long lastModified) throws Exception {
        var admission = service.admit(group.id(), path, new ResumableUploadAdmissionRequest(
                path.substring(path.lastIndexOf('/') + 1), "text/plain", 3, lastModified, "a".repeat(64), null, null));
        Path staged = storage.resumableUploadStagingFile(admission.sessionId());
        Files.writeString(staged, "abc");
        uploads.markStaged(admission.sessionId(), staged);
        assertThat(uploads.finalizeStaged(admission.sessionId()).status()).isEqualTo(ResumableUploadStatus.DIRECTORY_READY);
        return admission.sessionId();
    }

    @Test void onlyPublishesWholeRootWhenAllFilesAreReady() throws Exception {
        var group = service.create("", manifest("photos"));
        String first = receive(group, "nested/a.txt", 1);
        assertThat(service.get(group.id()).status()).isEqualTo(DirectoryUpload.Status.RECEIVING);
        assertThat(root.resolve("photos")).doesNotExist();
        assertThat(service.get(group.id()).files().stream().filter(e -> e.path().equals("nested/a.txt")).findFirst().orElseThrow().received()).isTrue();
        assertThat(uploads.require(first).status()).isEqualTo(ResumableUploadStatus.DIRECTORY_READY);
        receive(group, "b.txt", 2);
        var result = service.complete(group.id());
        assertThat(result.status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
        assertThat(root.resolve("photos/empty")).isEmptyDirectory();
        assertThat(Files.readString(root.resolve("photos/nested/a.txt"))).isEqualTo("abc");
        assertThat(root.resolve("photos/b.txt")).hasContent("abc");
        assertThat(journals.list()).isEmpty();
        assertThat(service.complete(group.id())).isEqualTo(result);
    }

    @Test void newLimitsAndDisableDoNotInvalidateAnAdmittedGroup() throws Exception {
        var input = manifest("photos");
        var group = service.create("", input);
        properties.getUpload().setDirectoryEnabled(false);
        properties.getUpload().setDirectoryMaxEntries(1);
        properties.getUpload().setDirectoryMaxDepth(1);
        properties.getUpload().setDirectoryMaxActive(1);
        assertThatThrownBy(() -> service.create("", input)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var resumed = service.create("", new DirectoryUploadManifest(input.name(), input.files(), input.directories(), group.id()));
        assertThat(resumed.id()).isEqualTo(group.id());
        receive(group, "nested/a.txt", 1);
        receive(group, "b.txt", 2);
        assertThat(service.complete(group.id()).status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admittedDirectoryAndLaterMembersKeepTheirFilesystemPolicy(boolean admittedPolicy) throws Exception {
        boolean effectivePolicy = FileSystemOperations.operation(admittedPolicy).nativeEnabled();
        DirectoryUpload group;
        try (var ignored = FileSystemOperations.operation(admittedPolicy).activate()) {
            group = service.create("", manifest("photos"));
        }
        assertThat(group.nativeEnabled()).isEqualTo(effectivePolicy);

        // Later member admissions and publication may run under a newly enabled caller policy.
        try (var ignored = FileSystemOperations.operation(!admittedPolicy).activate()) {
            String first = receive(group, "nested/a.txt", 1);
            String second = receive(group, "b.txt", 2);
            assertThat(uploads.require(first).nativeEnabled()).isEqualTo(effectivePolicy);
            assertThat(uploads.require(second).nativeEnabled()).isEqualTo(effectivePolicy);
            var result = service.get(group.id());
            assertThat(result.status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
            assertThat(result.nativeEnabled()).isEqualTo(effectivePolicy);
        }
        assertThat(repository.require(group.id()).nativeEnabled()).isEqualTo(effectivePolicy);
        assertThat(root.resolve("photos/b.txt")).hasContent("abc");
    }

    @Test void legacyDirectoryMetadataCapturesPolicyBeforeAdmittingMembers() throws Exception {
        var created = service.create("", manifest("photos"));
        var legacy = new DirectoryUpload(created.id(), created.destinationPath(), created.name(),
                created.files(), created.directories(), created.createdAt(), created.expiresAt(),
                created.status(), created.committedPath(), created.pendingDecisionId());
        repository.save(legacy);
        Path metadata = root.resolve(properties.getStorage().getMetadataDirectory())
                .resolve("directory-uploads").resolve(legacy.id() + ".json");
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(metadata));
        tree.remove("nativeEnabled");
        String json = mapper.writeValueAsString(tree);
        assertThat(json).doesNotContain("nativeEnabled");
        Files.writeString(metadata, json);
        assertThat(repository.require(legacy.id()).nativeEnabled()).isNull();

        try (var ignored = FileSystemOperations.operation(false).activate()) {
            String member = receive(legacy, "nested/a.txt", 1);
            assertThat(uploads.require(member).nativeEnabled()).isFalse();
        }
        assertThat(repository.require(legacy.id()).nativeEnabled()).isFalse();
        try (var ignored = FileSystemOperations.operation(true).activate()) {
            receive(legacy, "b.txt", 2);
            assertThat(service.complete(legacy.id()).nativeEnabled()).isFalse();
        }
    }

    @Test void directoryCompletionLogsRootPublicationUsingTheGroupPolicy() throws Exception {
        var activity = new ActivityLogService(JsonMapper.builder().findAndAddModules().build(), properties);
        activity.initialize();
        service = new DirectoryUploadService(repository, uploads, mock(ResumableUploadCoordinator.class), storage,
                commits, pending, artifacts, properties, activity);
        DirectoryUpload group;
        try (var ignored = FileSystemOperations.operation(false).activate()) {
            group = service.create("", manifest("photos"));
        }

        try (var ignored = FileSystemOperations.operation(true).activate()) {
            receive(group, "nested/a.txt", 1);
            receive(group, "b.txt", 2);
            assertThat(service.complete(group.id()).status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
        }

        var entries = activity.recentCurrentEntries(20).stream()
                .filter(entry -> "UPLOAD".equals(entry.type())).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().metadataView())
                .containsEntry("fs.nativeEnabled", "false")
                .containsEntry("fs.backend", "JAVA")
                .containsEntry("fs.jnaOperations", "0")
                .containsEntry("fs.javaOperations", "1");
        assertThat(root.resolve("photos/b.txt")).hasContent("abc");
    }

    @Test void newGroupsRespectConfiguredEntryDepthAndActiveLimits() throws Exception {
        properties.getUpload().setDirectoryMaxEntries(4);
        assertThatThrownBy(() -> service.create("", manifest("photos")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("entries");
        properties.getUpload().setDirectoryMaxEntries(10000);
        properties.getUpload().setDirectoryMaxDepth(1);
        assertThatThrownBy(() -> service.create("", manifest("photos")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("depth");
        properties.getUpload().setDirectoryMaxDepth(64);
        properties.getUpload().setDirectoryMaxActive(1);
        var group = service.create("", manifest("photos"));
        assertThatThrownBy(() -> service.create("", manifest("other")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("active");
        service.cancel(group.id());
        assertThat(service.create("", manifest("other")).status()).isEqualTo(DirectoryUpload.Status.RECEIVING);
    }

    @Test void collisionKeepsWholeDirectoryPendingWithoutMerging() throws Exception {
        Files.createDirectory(root.resolve("photos"));
        Files.writeString(root.resolve("photos/existing.txt"), "original");
        var group = service.create("", manifest("photos"));
        receive(group, "nested/a.txt", 1);
        receive(group, "b.txt", 2);
        var result = service.complete(group.id());
        assertThat(result.status()).isEqualTo(DirectoryUpload.Status.PENDING);
        assertThat(root.resolve("photos/b.txt")).doesNotExist();
        assertThat(pending.require(result.pendingDecisionId()).directory()).isTrue();
        assertThat(journals.list()).isEmpty();
        service.recoverAndCleanup();
        assertThat(pending.list()).hasSize(1);
        pending.resolve(result.pendingDecisionId(), PendingFileDecisionAction.KEEP_BOTH, null, false);
        assertThat(root.resolve("photos - 1/b.txt")).hasContent("abc");
    }

    @Test void resumesFromDurableMemberReceiptsAndRejectsChangedManifest() throws Exception {
        var group = service.create("", manifest("photos"));
        receive(group, "nested/a.txt", 1);
        service.get(group.id());
        service.close();
        service = service();
        var original = manifest("photos");
        var resumed = service.create("", new DirectoryUploadManifest(original.name(), original.files(), original.directories(), group.id()));
        assertThat(resumed.files().stream().filter(e -> e.path().equals("nested/a.txt")).findFirst().orElseThrow().received()).isTrue();
        assertThatThrownBy(() -> service.create("", new DirectoryUploadManifest("other", original.files(), original.directories(), group.id())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        receive(group, "b.txt", 2);
        assertThat(service.complete(group.id()).status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
    }

    @Test void recoversHardLinkCreatedBeforeReceiptWasSaved() throws Exception {
        var group = service.create("", manifest("photos"));
        String id = receive(group, "nested/a.txt", 1);
        Path source = storage.resumableUploadStagingFile(id);
        Path stagedRoot = storage.resolveFileStagingFile("directory-upload-" + group.id());
        Files.createLink(stagedRoot.resolve("nested/a.txt"), source);
        assertThat(service.get(group.id()).files().stream().filter(e -> e.path().equals("nested/a.txt")).findFirst().orElseThrow().received()).isTrue();
        assertThat(source).doesNotExist();
    }

    @Test void recoversDirectoryMovedBeforeGroupStateWasSaved() throws Exception {
        var group = service.create("", new DirectoryUploadManifest("empty", List.of(), List.of("child"), null));
        repository.save(group.withStatus(DirectoryUpload.Status.COMMITTING, null, null));
        commits.commitSingleDirectory(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_UPLOAD, group.id()),
                storage.resolveFileStagingFile("directory-upload-" + group.id()), "", "empty");
        service.recoverAndCleanup();
        assertThat(repository.require(group.id()).status()).isEqualTo(DirectoryUpload.Status.COMPLETED);
        assertThat(root.resolve("empty/child")).isEmptyDirectory();
        assertThat(journals.list()).isEmpty();
    }

    @Test void cancelRemovesOnlyStagingAndExpiryDoesNotDeletePending() throws Exception {
        var group = service.create("", manifest("photos"));
        String member = receive(group, "nested/a.txt", 1);
        service.get(group.id());
        assertThat(service.cancel(group.id()).status()).isEqualTo(DirectoryUpload.Status.CANCELED);
        assertThat(storage.resolveFileStagingFile("directory-upload-" + group.id())).doesNotExist();
        assertThatThrownBy(() -> uploads.require(member)).isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(root.resolve("photos")).doesNotExist();
        Files.createDirectory(root.resolve("empty"));
        var empty = service.create("", new DirectoryUploadManifest("empty", List.of(), List.of(), null));
        empty = service.complete(empty.id());
        repository.save(new DirectoryUpload(empty.id(), empty.destinationPath(), empty.name(), empty.files(), empty.directories(),
                empty.createdAt(), Instant.now().minusSeconds(1), empty.status(), empty.committedPath(), empty.pendingDecisionId()));
        service.recoverAndCleanup();
        assertThat(pending.require(empty.pendingDecisionId()).directory()).isTrue();
    }

    @Test void rejectsUndeclaredAndChangedFilesBeforeAdmission() throws Exception {
        var group = service.create("", manifest("photos"));
        var request = new ResumableUploadAdmissionRequest("b.txt", "text/plain", 4, 2, "a".repeat(64), null, null);
        assertThatThrownBy(() -> service.admit(group.id(), "b.txt", request)).isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> service.admit(group.id(), "../b.txt", request)).isInstanceOf(StorageAccessException.class);
        assertThat(uploads.list()).isEmpty();
    }

    @Test void expirationCancelsPartialDirectoryWithoutPublishingIt() throws Exception {
        var group = service.create("", manifest("unfinished"));
        receive(group, "nested/a.txt", 1);
        service.get(group.id());
        group = repository.require(group.id());
        repository.save(new DirectoryUpload(group.id(), group.destinationPath(), group.name(), group.files(), group.directories(),
                group.createdAt(), Instant.now().minusSeconds(1), group.status(), null, null));
        service.recoverAndCleanup();
        assertThat(repository.ids()).doesNotContain(group.id());
        assertThat(root.resolve("unfinished")).doesNotExist();
        assertThat(storage.resolveFileStagingFile("directory-upload-" + group.id())).doesNotExist();
        assertThat(uploads.list()).isEmpty();
    }

    @Test void unexpectedSameSizeStagingFileIsNotAdopted() throws Exception {
        var group = service.create("", manifest("photos"));
        receive(group, "nested/a.txt", 1);
        Files.writeString(storage.resolveFileStagingFile("directory-upload-" + group.id()).resolve("nested/a.txt"), "xyz");
        assertThatThrownBy(() -> service.get(group.id())).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("photos")).doesNotExist();
    }

    @Test void receivedFileDeletedOrTruncatedBeforeCompletionIsNotPublished() throws Exception {
        var group = service.create("", manifest("photos"));
        receive(group, "nested/a.txt", 1);
        service.get(group.id());
        Path assembled = storage.resolveFileStagingFile("directory-upload-" + group.id()).resolve("nested/a.txt");
        Files.writeString(assembled, "x");
        receive(group, "b.txt", 2);
        assertThatThrownBy(() -> service.complete(group.id())).isInstanceOf(StorageAccessException.class);
        Files.delete(assembled);
        assertThatThrownBy(() -> service.complete(group.id())).isInstanceOf(StorageAccessException.class);
        assertThat(root.resolve("photos")).doesNotExist();
    }

    @Test void missingDestinationBeforeJournalDoesNotPreventCancellation() throws Exception {
        Files.createDirectory(root.resolve("destination"));
        var group = service.create("destination", new DirectoryUploadManifest("empty", List.of(), List.of(), null));
        Files.delete(root.resolve("destination"));
        assertThatThrownBy(() -> service.complete(group.id())).isInstanceOf(Exception.class);
        assertThat(journals.list()).isEmpty();
        assertThat(service.cancel(group.id()).status()).isEqualTo(DirectoryUpload.Status.CANCELED);
        assertThat(storage.resolveFileStagingFile("directory-upload-" + group.id())).doesNotExist();
    }

    @Test void rejectsTraversalReservedNamesCaseCollisionsAndFileDirectoryCollisions() {
        for (String path : List.of("../a", "/a", "a//b", "a/CON", "a\\b", "a/..", "a/")) {
            assertThatThrownBy(() -> service.create("", new DirectoryUploadManifest("folder", List.of(
                    new DirectoryUploadManifest.File(path, 1, 0)), List.of(), null))).isInstanceOf(StorageAccessException.class);
        }
        assertThatThrownBy(() -> service.create("", new DirectoryUploadManifest("folder", List.of(
                new DirectoryUploadManifest.File("A", 1, 0), new DirectoryUploadManifest.File("a", 1, 0)), List.of(), null)))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> service.create("", new DirectoryUploadManifest("folder", List.of(
                new DirectoryUploadManifest.File("a", 1, 0), new DirectoryUploadManifest.File("a/b", 1, 0)), List.of(), null)))
                .isInstanceOf(StorageAccessException.class);
    }
}
