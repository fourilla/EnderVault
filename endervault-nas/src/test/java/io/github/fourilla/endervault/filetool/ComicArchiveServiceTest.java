package io.github.fourilla.endervault.filetool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComicArchiveServiceTest {

    @TempDir
    Path root;

    private ComicArchiveService comicArchiveService;

    @BeforeEach
    void setUp() {
        NasProperties properties = new NasProperties();
        properties.getFileTools().setComicMaxPages(20);
        comicArchiveService = new ComicArchiveService(properties);
    }

    @Test
    void manifestSortsImagePagesNaturallyAndParsesInfoText() throws Exception {
        Path cbz = root.resolve("comic.cbz");
        writeCbz(
                cbz,
                entry("pages/10.jpg", "ten"),
                entry("pages/2.jpg", "two"),
                entry("pages/1.jpg", "one"),
                entry("info.txt", "title: Sample Comic\nauthor=EnderVault\nnote without separator")
        );

        ComicArchiveManifest manifest = comicArchiveService.manifest(cbz);

        assertThat(manifest.pageCount()).isEqualTo(3);
        assertThat(manifest.pages())
                .extracting(ComicPage::displayName)
                .containsExactly("1.jpg", "2.jpg", "10.jpg");
        assertThat(manifest.metadata().present()).isTrue();
        assertThat(manifest.metadata().entries())
                .extracting(ComicMetadataEntry::name, ComicMetadataEntry::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("title", "Sample Comic"),
                        org.assertj.core.groups.Tuple.tuple("author", "EnderVault")
                );
    }

    @Test
    void openPageStreamsTheSelectedZipEntryOnly() throws Exception {
        Path cbz = root.resolve("comic.cbz");
        writeCbz(
                cbz,
                entry("1.jpg", "one"),
                entry("2.png", "two")
        );

        ComicPageResource page = comicArchiveService.openPage(cbz, 1);

        assertThat(page.mediaType()).isEqualTo("image/png");
        try (InputStream inputStream = page.resource().getInputStream()) {
            assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("two");
        }
    }

    private void writeCbz(Path target, TestEntry... entries) throws Exception {
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(target))) {
            for (TestEntry entry : entries) {
                outputStream.putNextEntry(new ZipEntry(entry.name()));
                outputStream.write(entry.content().getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }
        }
    }

    private TestEntry entry(String name, String content) {
        return new TestEntry(name, content);
    }

    private record TestEntry(String name, String content) {
    }
}
