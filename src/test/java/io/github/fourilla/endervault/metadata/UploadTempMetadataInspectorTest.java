package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadTempMetadataInspectorTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private StorageService storageService;
    private UploadTempMetadataInspector inspector;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getMetadataInspector().setUploadTempStaleMinutes(1);
        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), temporaryArtifactRegistry);
        storageService.initialize();
        inspector = new UploadTempMetadataInspector(storageService, properties);
    }

    @Test
    void activeStaleArtifactIsInformationalAndCannotBeDeleted() throws Exception {
        Path temporaryFile = storageService.createUploadTemporaryFile("remote-download-", ".tmp");
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
        assertThat(activeIssue.title()).isEqualTo("Active temporary operation file");
        assertThat(activeIssue.recommendation()).contains("Remote download");
        assertThatThrownBy(() -> storageService.deleteUploadTemporaryFile(temporaryFile.getFileName().toString()))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("still in use");

        registration.close();
        MetadataIssue staleIssue = inspector.inspect().getFirst();
        assertThat(staleIssue.severity()).isEqualTo(MetadataIssueSeverity.WARNING);
        assertThat(staleIssue.action()).isEqualTo(MetadataIssueAction.DELETE_UPLOAD_TEMP);

        inspector.repair(staleIssue.action(), staleIssue.subject());
        assertThat(temporaryFile).doesNotExist();
    }
}
