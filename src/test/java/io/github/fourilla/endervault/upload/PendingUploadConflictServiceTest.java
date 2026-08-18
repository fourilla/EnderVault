package io.github.fourilla.endervault.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import io.github.fourilla.endervault.upload.PendingUploadConflictService.PendingUploadConflict;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingUploadConflictServiceTest {

    @TempDir
    Path root;

    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private PendingUploadConflictService service;

    @BeforeEach
    void setUp() {
        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        service = new PendingUploadConflictService(new NasProperties(), temporaryArtifactRegistry);
    }

    @Test
    void keepsStagedFileActiveUntilResolvedConflictIsReleased() throws Exception {
        Path temporaryFile = Files.createTempFile(root, "upload-", ".tmp");
        PendingUploadConflict created = service.create(temporaryFile, "docs", "note.txt", 10L);

        assertThat(temporaryArtifactRegistry.find(temporaryFile))
                .get()
                .extracting(artifact -> artifact.type())
                .isEqualTo(TemporaryArtifactType.UPLOAD_CONFLICT);

        PendingUploadConflict resolved = service.resolve(created.id());
        assertThat(temporaryArtifactRegistry.isActive(temporaryFile)).isTrue();

        service.release(resolved);
        assertThat(temporaryArtifactRegistry.isActive(temporaryFile)).isFalse();
    }

    @Test
    void cancelDeletesStagedFileAndReleasesRegistration() throws Exception {
        Path temporaryFile = Files.createTempFile(root, "upload-", ".tmp");
        PendingUploadConflict conflict = service.create(temporaryFile, "", "note.txt", 10L);

        service.cancel(conflict.id());

        assertThat(temporaryFile).doesNotExist();
        assertThat(temporaryArtifactRegistry.isActive(temporaryFile)).isFalse();
    }
}
