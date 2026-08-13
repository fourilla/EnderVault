package io.github.fourilla.endervault.filetool.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveExtractionGuardTest {

    @TempDir
    Path outputRoot;

    @Test
    void writesRegularFilesInsideTheOutputDirectory() throws Exception {
        ArchiveExtractionGuard guard = guard(1024, 4096);

        guard.copyFile(
                "docs/readme.txt",
                5,
                5,
                null,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(outputRoot.resolve("docs/readme.txt")).hasContent("hello");
    }

    @Test
    void rejectsTraversalBeforeOpeningAnOutputFile() {
        ArchiveExtractionGuard guard = guard(1024, 4096);

        assertThatThrownBy(() -> guard.copyFile(
                "../outside.txt",
                1,
                1,
                null,
                new ByteArrayInputStream(new byte[] {1})
        )).isInstanceOf(StorageAccessException.class);

        assertThat(Files.exists(outputRoot.getParent().resolve("outside.txt"))).isFalse();
    }

    @Test
    void enforcesTheActualBytesReadEvenWhenMetadataUnderstatesSize() {
        ArchiveExtractionGuard guard = guard(4, 4096);

        assertThatThrownBy(() -> guard.copyFile(
                "large.bin",
                1,
                1,
                null,
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void enforcesTheActualTotalBytesAcrossEntries() throws Exception {
        ArchiveExtractionGuard guard = guard(8, 6);
        guard.copyFile("one.bin", 3, 3, null, new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThatThrownBy(() -> guard.copyFile(
                "two.bin",
                1,
                1,
                null,
                new ByteArrayInputStream(new byte[] {4, 5, 6, 7})
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("total size limit");
    }

    private ArchiveExtractionGuard guard(long entryBytes, long totalBytes) {
        return new ArchiveExtractionGuard(
                outputRoot,
                new ArchiveLimits(100, entryBytes, totalBytes, 1000, 262144),
                ArchiveExtractionProgress.NOOP
        );
    }
}
