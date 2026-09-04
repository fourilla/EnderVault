package io.github.fourilla.endervault.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingFileDecisionServiceTest {

    @TempDir
    Path root;

    private NasProperties properties;
    private StorageService storageService;
    private ObjectMapper objectMapper;
    private FileCommitCoordinator fileCommitCoordinator;
    private FileCommitJournalStore fileCommitJournalStore;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        storageService = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storageService.initialize();
        fileCommitJournalStore = new FileCommitJournalStore(objectMapper, properties);
        fileCommitJournalStore.initialize();
        fileCommitCoordinator = new FileCommitCoordinator(fileCommitJournalStore, storageService, properties);
    }

    @Test
    void restoresPendingDecisionAndStagingRegistrationAfterRestart() throws Exception {
        Files.writeString(root.resolve("note.txt"), "existing");
        TemporaryArtifactRegistry firstArtifacts = new TemporaryArtifactRegistry();
        PendingFileDecisionService first = service(firstArtifacts);
        Path staged = storageService.createFileStagingTemporaryFile("upload-", ".tmp");
        Files.writeString(staged, "pending");

        PendingFileDecision decision = first.create(
                staged,
                PendingFileDecisionSource.ADMIN_UPLOAD,
                "",
                "note.txt",
                Files.size(staged)
        );
        assertThat(firstArtifacts.find(staged))
                .get()
                .extracting(artifact -> artifact.type())
                .isEqualTo(TemporaryArtifactType.PENDING_FILE_DECISION);
        first.closeRegistrations();

        TemporaryArtifactRegistry restartedArtifacts = new TemporaryArtifactRegistry();
        PendingFileDecisionService restarted = service(restartedArtifacts);

        assertThat(restarted.list()).extracting(PendingFileDecision::id).containsExactly(decision.id());
        assertThat(restartedArtifacts.isActive(staged)).isTrue();

        PendingFileDecisionService.PendingFileDecisionResult result = restarted.resolve(
                decision.id(),
                PendingFileDecisionAction.KEEP_BOTH,
                null,
                false
        );
        assertThat(result.committedFile().name()).isNotEqualTo("note.txt");
        assertThat(Files.readString(root.resolve(result.committedFile().name()))).isEqualTo("pending");
        assertThat(restarted.list()).isEmpty();
        assertThat(restartedArtifacts.isActive(staged)).isFalse();
        assertThat(fileCommitJournalStore.list()).isEmpty();
    }

    @Test
    void discardDeletesStagedFileAndDecision() throws Exception {
        TemporaryArtifactRegistry artifacts = new TemporaryArtifactRegistry();
        PendingFileDecisionService service = service(artifacts);
        Path staged = storageService.createFileStagingTemporaryFile("request-", ".tmp");
        Files.writeString(staged, "pending");
        PendingFileDecision decision = service.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );

        PendingFileDecisionService.PendingFileDecisionResult result = service.resolve(
                decision.id(),
                PendingFileDecisionAction.DISCARD,
                null,
                false
        );

        assertThat(result.discarded()).isTrue();
        assertThat(staged).doesNotExist();
        assertThat(service.list()).isEmpty();
        assertThat(artifacts.isActive(staged)).isFalse();
    }

    @Test
    void saveAsCommitsUsingRequestedFilename() throws Exception {
        PendingFileDecisionService service = service(new TemporaryArtifactRegistry());
        Path staged = storageService.createFileStagingTemporaryFile("request-", ".tmp");
        Files.writeString(staged, "pending");
        PendingFileDecision decision = service.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );

        PendingFileDecisionService.PendingFileDecisionResult result = service.resolve(
                decision.id(),
                PendingFileDecisionAction.SAVE_AS,
                "renamed.txt",
                false
        );

        assertThat(result.committedFile().name()).isEqualTo("renamed.txt");
        assertThat(Files.readString(root.resolve("renamed.txt"))).isEqualTo("pending");
        assertThat(service.list()).isEmpty();
        assertThat(fileCommitJournalStore.list()).isEmpty();
    }

    @Test
    void replaceOverwritesUnchangedConflictTarget() throws Exception {
        Path target = root.resolve("note.txt");
        Files.writeString(target, "existing");
        PendingFileDecisionService service = service(new TemporaryArtifactRegistry());
        Path staged = storageService.createFileStagingTemporaryFile("request-", ".tmp");
        Files.writeString(staged, "pending");
        PendingFileDecision decision = service.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );

        PendingFileDecisionService.PendingFileDecisionResult result = service.resolve(
                decision.id(),
                PendingFileDecisionAction.REPLACE,
                null,
                true
        );

        assertThat(result.committedFile().name()).isEqualTo("note.txt");
        assertThat(Files.readString(target)).isEqualTo("pending");
        assertThat(service.list()).isEmpty();
        assertThat(fileCommitJournalStore.list()).isEmpty();
    }

    @Test
    void replaceRejectsTargetChangedAfterDecisionWasCreated() throws Exception {
        Path target = root.resolve("note.txt");
        Files.writeString(target, "existing");
        PendingFileDecisionService service = service(new TemporaryArtifactRegistry());
        Path staged = storageService.createFileStagingTemporaryFile("request-", ".tmp");
        Files.writeString(staged, "pending");
        PendingFileDecision decision = service.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );
        Files.writeString(target, "changed after review started");

        assertThatThrownBy(() -> service.resolve(
                decision.id(),
                PendingFileDecisionAction.REPLACE,
                null,
                true
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("changed");

        assertThat(staged).exists();
        assertThat(service.list()).extracting(PendingFileDecision::id).containsExactly(decision.id());
    }

    @Test
    void restartKeepsBrokenDecisionMetadataForInspectorRepair() throws Exception {
        TemporaryArtifactRegistry firstArtifacts = new TemporaryArtifactRegistry();
        PendingFileDecisionService first = service(firstArtifacts);
        Path staged = storageService.createFileStagingTemporaryFile("request-", ".tmp");
        Files.writeString(staged, "pending");
        PendingFileDecision decision = first.create(
                staged,
                PendingFileDecisionSource.FILE_REQUEST,
                "",
                "note.txt",
                Files.size(staged)
        );
        first.closeRegistrations();
        Files.delete(staged);

        PendingFileDecisionService restarted = service(new TemporaryArtifactRegistry());

        assertThat(restarted.list()).extracting(PendingFileDecision::id).containsExactly(decision.id());
        restarted.removeMissingData(decision.id());
        assertThat(restarted.list()).isEmpty();
    }

    private PendingFileDecisionService service(TemporaryArtifactRegistry artifacts) throws Exception {
        PendingFileDecisionRepository repository = new PendingFileDecisionRepository(objectMapper, properties);
        repository.initialize();
        PendingFileDecisionService service = new PendingFileDecisionService(
                repository,
                storageService,
                fileCommitCoordinator,
                artifacts,
                java.util.List.of()
        );
        service.restoreRegistrations();
        return service;
    }
}
