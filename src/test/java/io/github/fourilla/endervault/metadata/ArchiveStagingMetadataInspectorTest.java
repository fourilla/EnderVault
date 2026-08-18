package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveStagingMetadataInspectorTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry registry;
    private StorageService storageService;
    private ArchiveStagingMetadataInspector inspector;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTemporaryArtifacts().setStaleAfterMinutes(1);
        registry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), registry);
        storageService.initialize();
        inspector = new ArchiveStagingMetadataInspector(
                storageService,
                new TemporaryArtifactRetentionPolicy(properties)
        );
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
