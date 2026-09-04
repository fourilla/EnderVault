package io.github.fourilla.endervault.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookmarkFaviconCacheServiceTest {

    @TempDir
    Path metadataRoot;

    @Test
    void temporaryFilesAreListedAndActiveWritesCannotBeDeleted() throws Exception {
        TemporaryArtifactRegistry registry = new TemporaryArtifactRegistry();
        BookmarkFaviconCacheService service = new BookmarkFaviconCacheService(
                new ObjectMapper().findAndRegisterModules(),
                metadataRoot,
                "bookmark-favicons",
                registry
        );
        service.initialize();
        Path temporaryFile = metadataRoot.resolve("bookmark-favicons").resolve("favicon-test.tmp");
        Files.writeString(temporaryFile, "favicon");

        TemporaryArtifactRegistry.Registration registration = registry.register(
                temporaryFile,
                TemporaryArtifactType.BOOKMARK_FAVICON,
                "bookmark-1"
        );
        BookmarkFaviconTemporaryFile listed = service.temporaryFiles().getFirst();
        assertThat(listed.fileName()).isEqualTo("favicon-test.tmp");
        assertThat(listed.active()).isTrue();
        assertThat(listed.activeOperation()).isEqualTo("Bookmark favicon");
        assertThatThrownBy(() -> service.deleteOrphanFile("favicon-test.tmp", List.of()))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("still in use");

        registration.close();
        service.deleteOrphanFile("favicon-test.tmp", List.of());
        assertThat(temporaryFile).doesNotExist();
    }
}
