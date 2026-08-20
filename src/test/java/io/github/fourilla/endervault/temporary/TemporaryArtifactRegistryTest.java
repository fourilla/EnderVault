package io.github.fourilla.endervault.temporary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryArtifactRegistryTest {

    @TempDir
    Path root;

    @Test
    void registrationTracksNormalizedPathAndReleasesIdempotently() {
        TemporaryArtifactRegistry registry = new TemporaryArtifactRegistry();
        Path temporaryFile = root.resolve("work").resolve("..").resolve("download.tmp");

        TemporaryArtifactRegistry.Registration registration = registry.register(
                temporaryFile,
                TemporaryArtifactType.REMOTE_DOWNLOAD,
                "task-1"
        );

        assertThat(registry.isActive(root.resolve("download.tmp"))).isTrue();
        assertThat(registry.find(temporaryFile)).hasValueSatisfying(artifact -> {
            assertThat(artifact.path()).isEqualTo(root.resolve("download.tmp").toAbsolutePath().normalize());
            assertThat(artifact.type()).isEqualTo(TemporaryArtifactType.REMOTE_DOWNLOAD);
            assertThat(artifact.ownerId()).isEqualTo("task-1");
        });
        assertThat(registry.activeArtifacts()).hasSize(1);

        registration.close();
        registration.close();

        assertThat(registry.isActive(temporaryFile)).isFalse();
        assertThat(registry.activeArtifacts()).isEmpty();
    }

    @Test
    void rejectsConcurrentRegistrationForSameArtifact() {
        TemporaryArtifactRegistry registry = new TemporaryArtifactRegistry();
        Path temporaryFile = root.resolve("shared.tmp");

        TemporaryArtifactRegistry.Registration first = registry.register(
                temporaryFile,
                TemporaryArtifactType.ARCHIVE_CREATION,
                "first"
        );
        assertThatThrownBy(() -> registry.register(
                temporaryFile,
                TemporaryArtifactType.FILE_COPY,
                "second"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");

        first.close();
        try (TemporaryArtifactRegistry.Registration ignored = registry.register(
                temporaryFile,
                TemporaryArtifactType.FILE_COPY,
                "second"
        )) {
            assertThat(registry.find(temporaryFile))
                    .get()
                    .extracting(TemporaryArtifact::type)
                    .isEqualTo(TemporaryArtifactType.FILE_COPY);
        }
    }
}
