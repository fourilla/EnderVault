package io.github.fourilla.endervault.filetool.comic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    @Test
    void openPageEnforcesConfiguredLimitWhileStreaming() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getFileTools().setComicPageMaxBytes(1024);
        ComicArchiveService service = new ComicArchiveService(properties);

        Path cbz = root.resolve("oversized-page.cbz");
        byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'x');
        writeCbz(cbz, entry("1.jpg", new String(content, StandardCharsets.UTF_8)));
        rewriteCentralDirectoryUncompressedSize(cbz, "1.jpg", 8);

        ComicPageResource page = service.openPage(cbz, 0);

        assertThat(page.contentLength()).isEqualTo(8);
        assertThatThrownBy(() -> {
            try (InputStream inputStream = page.resource().getInputStream()) {
                inputStream.readAllBytes();
            }
        })
                .isInstanceOf(IOException.class)
                .hasMessageContaining("configured size limit");
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

    private void rewriteCentralDirectoryUncompressedSize(Path zipFile, String entryName, long size) throws Exception {
        byte[] zipBytes = Files.readAllBytes(zipFile);
        byte[] entryNameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        boolean replaced = false;
        for (int index = 0; index <= zipBytes.length - 46; index++) {
            if (littleEndianInt(zipBytes, index) != 0x02014b50) {
                continue;
            }

            int nameLength = littleEndianUnsignedShort(zipBytes, index + 28);
            int extraLength = littleEndianUnsignedShort(zipBytes, index + 30);
            int commentLength = littleEndianUnsignedShort(zipBytes, index + 32);
            int nameStart = index + 46;
            if (nameStart + nameLength > zipBytes.length) {
                break;
            }

            byte[] candidateName = Arrays.copyOfRange(zipBytes, nameStart, nameStart + nameLength);
            if (Arrays.equals(candidateName, entryNameBytes)) {
                writeLittleEndianUnsignedInt(zipBytes, index + 24, size);
                replaced = true;
                break;
            }
            index = nameStart + nameLength + extraLength + commentLength - 1;
        }

        assertThat(replaced).isTrue();
        Files.write(zipFile, zipBytes);
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private int littleEndianUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private void writeLittleEndianUnsignedInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xff);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private TestEntry entry(String name, String content) {
        return new TestEntry(name, content);
    }

    private record TestEntry(String name, String content) {
    }
}
