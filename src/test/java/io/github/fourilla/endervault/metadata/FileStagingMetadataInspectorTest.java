package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStagingMetadataInspectorTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private StorageService storageService;
    private FileStagingMetadataInspector inspector;
    private ResumableUploadService resumableUploadService;
    private FileCommitCoordinator fileCommitCoordinator;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTemporaryArtifacts().setStaleAfterMinutes(1);
        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), temporaryArtifactRegistry);
        storageService.initialize();
        resumableUploadService = mock(ResumableUploadService.class);
        when(resumableUploadService.activeStagingFilenames()).thenReturn(Set.of());
        fileCommitCoordinator = mock(FileCommitCoordinator.class);
        when(fileCommitCoordinator.activeStagingFilenames()).thenReturn(Set.of());
        inspector = new FileStagingMetadataInspector(
                storageService,
                new TemporaryArtifactRetentionPolicy(properties),
                resumableUploadService,
                fileCommitCoordinator
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

    @Test
    void resumableUploadStagingIsInformationalAndCannotBeRepaired() throws Exception {
        String filename = "resumable-11111111-1111-1111-1111-111111111111.tmp";
        Path temporaryFile = storageService.resolveFileStagingFile(filename);
        Files.writeString(temporaryFile, "waiting for finalization");
        Files.setLastModifiedTime(temporaryFile, FileTime.from(Instant.now().minusSeconds(3600)));
        when(resumableUploadService.activeStagingFilenames()).thenReturn(Set.of(filename));
        when(resumableUploadService.referencesStagingFile(filename)).thenReturn(true);

        MetadataIssue issue = inspector.inspect().getFirst();

        assertThat(issue.severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(issue.action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issue.recommendation()).contains("Resumable upload");
        assertThatThrownBy(() -> inspector.repair(MetadataIssueAction.DELETE_FILE_STAGING, filename))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("resumable upload");
        assertThat(temporaryFile).exists();
    }

    @Test
    void fileCommitJournalStagingIsInformationalAndCannotBeRepaired() throws Exception {
        String filename = "remote-download-journal.tmp";
        Path temporaryFile = storageService.resolveFileStagingFile(filename);
        Files.writeString(temporaryFile, "waiting for recovery");
        Files.setLastModifiedTime(temporaryFile, FileTime.from(Instant.now().minusSeconds(3600)));
        when(fileCommitCoordinator.activeStagingFilenames()).thenReturn(Set.of(filename));
        when(fileCommitCoordinator.referencesStagingFile(filename)).thenReturn(true);

        MetadataIssue issue = inspector.inspect().getFirst();

        assertThat(issue.severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(issue.action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issue.recommendation()).contains("File commit recovery");
        assertThatThrownBy(() -> inspector.repair(MetadataIssueAction.DELETE_FILE_STAGING, filename))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("file commit journal");
        assertThat(temporaryFile).exists();
    }
}
