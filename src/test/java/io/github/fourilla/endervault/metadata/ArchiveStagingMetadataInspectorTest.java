package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitBatchCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.ArchiveCommitPlan;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageBatchEntry;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveStagingMetadataInspectorTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry registry;
    private StorageService storageService;
    private ArchiveStagingMetadataInspector inspector;
    private FileCommitBatchCoordinator batchCoordinator;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTemporaryArtifacts().setStaleAfterMinutes(1);
        registry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), registry);
        storageService.initialize();
        FileCommitJournalStore journalStore = new FileCommitJournalStore(
                JsonMapper.builder().findAndAddModules().build(), properties
        );
        journalStore.initialize();
        FileCommitCoordinator coordinator = new FileCommitCoordinator(journalStore, storageService, properties);
        batchCoordinator = new FileCommitBatchCoordinator(
                journalStore, coordinator, storageService
        );
        inspector = new ArchiveStagingMetadataInspector(
                storageService,
                new TemporaryArtifactRetentionPolicy(properties),
                batchCoordinator
        );
    }

    @Test
    void journalOwnedWorkspaceCannotBeRepairedAsStale() throws Exception {
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("note.txt"), "note");
        ArchiveCommitPlan plan = storageService.planArchiveCommit(
                content,
                "",
                false,
                "ignored",
                List.of(new StorageBatchEntry("note.txt", false)),
                ConflictPolicy.CANCEL
        );
        batchCoordinator.commitArchiveExtraction(
                new FileCommitOwner(FileCommitOwnerType.ARCHIVE_EXTRACT, "task-1"),
                plan,
                ConflictPolicy.CANCEL,
                StorageProgressListener.NOOP
        );
        Files.setLastModifiedTime(workspace, FileTime.from(Instant.now().minusSeconds(3600)));

        MetadataIssue issue = inspector.inspect().getFirst();

        assertThat(issue.severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(issue.action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issue.recommendation()).contains("File commit recovery");
        assertThatThrownBy(() -> inspector.repair(
                MetadataIssueAction.DELETE_ARCHIVE_STAGING,
                workspace.getFileName().toString()
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("protected by a file commit journal");
    }

    @Test
    void activeWorkspaceIsProtectedAndStaleInactiveWorkspaceCanBeDeleted() throws Exception {
        Path workspace = storageService.createArchiveCreationWorkspace();
        Files.setLastModifiedTime(workspace, FileTime.from(Instant.now().minusSeconds(3600)));
        TemporaryArtifactRegistry.Registration registration = registry.register(
                workspace,
                TemporaryArtifactType.ARCHIVE_CREATION,
                "task-1"
        );

        MetadataIssue activeIssue = inspector.inspect().getFirst();
        assertThat(activeIssue.severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(activeIssue.action()).isEqualTo(MetadataIssueAction.NONE);
        assertThatThrownBy(() -> storageService.deleteArchiveStagingArtifact(workspace.getFileName().toString()))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("still in use");

        registration.close();
        MetadataIssue staleIssue = inspector.inspect().getFirst();
        assertThat(staleIssue.severity()).isEqualTo(MetadataIssueSeverity.WARNING);
        assertThat(staleIssue.action()).isEqualTo(MetadataIssueAction.DELETE_ARCHIVE_STAGING);

        inspector.repair(staleIssue.action(), staleIssue.subject());
        assertThat(workspace).doesNotExist();
    }
}
