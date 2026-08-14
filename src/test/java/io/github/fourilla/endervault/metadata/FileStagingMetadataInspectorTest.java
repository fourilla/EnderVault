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

class FileStagingMetadataInspectorTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private StorageService storageService;
    private FileStagingMetadataInspector inspector;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTemporaryArtifacts().setStaleAfterMinutes(1);
        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), temporaryArtifactRegistry);
        storageService.initialize();
        inspector = new FileStagingMetadataInspector(
                storageService,
                new TemporaryArtifactRetentionPolicy(properties)
        );
    }

    @Test
    void activeStaleArtifactIsInformationalAndCannotBeDeleted() throws Exception {
        Path temporaryFile = storageService.createFileStagingTemporaryFile("remote-download-", ".tmp");
        Files.writeString(temporaryFile, "in progress");
        Files.setLastModifiedTime(temporaryFile, FileTime.from(Instant.now().minusSeconds(3600)));

        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.REMOTE_DOWNLOAD,
                "task-1"
        );
        MetadataIssue activeIssue = inspector.inspect().getFirst();

        assertThat(activeIssue.severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(activeIssue.action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(activeIssue.title()).isEqualTo("Active file staging artifact");
        assertThat(activeIssue.recommendation()).contains("Remote download");
        assertThatThrownBy(() -> storageService.deleteFileStagingFile(temporaryFile.getFileName().toString()))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("still in use");

        registration.close();
        MetadataIssue staleIssue = inspector.inspect().getFirst();
        assertThat(staleIssue.severity()).isEqualTo(MetadataIssueSeverity.WARNING);
        assertThat(staleIssue.action()).isEqualTo(MetadataIssueAction.DELETE_FILE_STAGING);

        inspector.repair(staleIssue.action(), staleIssue.subject());
        assertThat(temporaryFile).doesNotExist();
    }
}
