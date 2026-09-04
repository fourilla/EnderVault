package io.github.fourilla.endervault.filetool.archive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveServiceTest {

    @TempDir
    Path temporaryDirectory;

    private ArchiveService archiveService;

    @BeforeEach
    void setUp() {
        NasProperties properties = new NasProperties();
        archiveService = new ArchiveService(
                List.of(new ZipArchiveBackend(), new SevenZipArchiveBackend(), new TarArchiveBackend()),
                properties
        );
    }

    @Test
    void buildsVirtualDirectoriesAndReturnsOnlyDirectChildren() throws Exception {
        Path archive = zip("sample.zip", List.of(
                entry("docs/readme.txt", "hello"),
                entry("root.txt", "root")
        ));

        ArchiveManifest manifest = archiveService.manifest(archive);

        assertThat(manifest.extractable()).isTrue();
        assertThat(manifest.fileCount()).isEqualTo(2);
        assertThat(manifest.directoryCount()).isEqualTo(1);
        assertThat(manifest.children("")).extracting(ArchiveEntryInfo::name)
                .containsExactly("docs", "root.txt");
        assertThat(manifest.children("docs")).extracting(ArchiveEntryInfo::name)
                .containsExactly("readme.txt");
    }

    @Test
    void sortsArchiveChildrenByNaturalNameOrder() throws Exception {
        Path archive = zip("natural.zip", List.of(
                entry("pages/11.txt", "eleven"),
                entry("pages/3.txt", "three"),
                entry("pages/2.txt", "two")
        ));

        ArchiveManifest manifest = archiveService.manifest(archive);

        assertThat(manifest.children("pages")).extracting(ArchiveEntryInfo::name)
                .containsExactly("2.txt", "3.txt", "11.txt");
    }

    @Test
    void rejectsZipSlipPathsWithoutHidingSafeEntries() throws Exception {
        Path archive = zip("unsafe.zip", List.of(
                entry("safe.txt", "safe"),
                entry("../outside.txt", "unsafe")
        ));

        ArchiveManifest manifest = archiveService.manifest(archive);

        assertThat(manifest.browsable()).isTrue();
        assertThat(manifest.extractable()).isFalse();
        assertThat(manifest.rejectedEntryCount()).isEqualTo(1);
        assertThat(manifest.children("")).extracting(ArchiveEntryInfo::name)
                .containsExactly("safe.txt");
        assertThat(manifest.message()).contains("unsafe path segment");
    }

    @Test
    void keepsEncryptedZipEntriesBrowseableWhenTheirNamesAreReadable() throws Exception {
        Path archive = zip("encrypted.zip", List.of(
                entry("library/series/volume/chapter.txt", "encrypted content"),
                entry("library/index.txt", "encrypted index")
        ));
        markZipEntriesEncrypted(archive);

        ArchiveManifest manifest = archiveService.manifest(archive);

        assertThat(manifest.browsable()).isTrue();
        assertThat(manifest.extractable()).isFalse();
        assertThat(manifest.rejectedEntryCount()).isZero();
        assertThat(manifest.fileCount()).isEqualTo(2);
        assertThat(manifest.children("library")).extracting(ArchiveEntryInfo::name)
                .containsExactly("series", "index.txt");
        assertThat(manifest.children("library/series/volume")).extracting(ArchiveEntryInfo::name)
                .containsExactly("chapter.txt");
        assertThat(manifest.message()).contains("encrypted or unsupported entries");
    }

    @Test
    void clearsEntriesWhenArchiveMetadataCannotBeBrowsed() {
        NasProperties properties = new NasProperties();
        ArchiveManifestBuilder builder = new ArchiveManifestBuilder(
                ArchiveFormat.SEVEN_ZIP,
                ArchiveLimits.from(properties.getFileTools())
        );
        builder.add("visible-before-lock.txt", false, 10L, 5L, null);

        builder.markUnreadable("A password is required before this archive can be browsed.");
        ArchiveManifest manifest = builder.build();

        assertThat(manifest.browsable()).isFalse();
        assertThat(manifest.extractable()).isFalse();
        assertThat(manifest.entries()).isEmpty();
        assertThat(manifest.fileCount()).isZero();
        assertThat(manifest.message()).contains("password is required");
    }

    @Test
    void rejectsPathsUsedAsBothFilesAndDirectories() throws Exception {
        Path archive = zip("collision.zip", List.of(
                entry("node", "file"),
                entry("node/child.txt", "child")
        ));

        ArchiveManifest manifest = archiveService.manifest(archive);

        assertThat(manifest.extractable()).isFalse();
        assertThat(manifest.message()).contains("both a file and a directory");
    }

    @Test
    void detectsAllSupportedFilenameForms() {
        assertThat(ArchiveFormat.fromFilename("data.zip")).contains(ArchiveFormat.ZIP);
        assertThat(ArchiveFormat.fromFilename("data.7z")).contains(ArchiveFormat.SEVEN_ZIP);
        assertThat(ArchiveFormat.fromFilename("data.tar")).contains(ArchiveFormat.TAR);
        assertThat(ArchiveFormat.fromFilename("data.tar.gz")).contains(ArchiveFormat.TAR_GZIP);
        assertThat(ArchiveFormat.fromFilename("data.tgz")).contains(ArchiveFormat.TAR_GZIP);
        assertThat(ArchiveFormat.fromFilename("data.tbz2")).contains(ArchiveFormat.TAR_BZIP2);
        assertThat(ArchiveFormat.fromFilename("data.txz")).contains(ArchiveFormat.TAR_XZ);
        assertThat(ArchiveFormat.fromFilename("comic.cbz")).isEmpty();
    }

    @Test
    void extractsZipEntriesThroughTheGuardedBackend() throws Exception {
        Path archive = zip("extract.zip", List.of(
                entry("docs/readme.txt", "hello"),
                entry("root.txt", "root")
        ));
        Path output = Files.createDirectory(temporaryDirectory.resolve("output"));
        NasProperties properties = new NasProperties();

        new ZipArchiveBackend().extract(
                archive,
                output,
                ArchiveFormat.ZIP,
                ArchiveLimits.from(properties.getFileTools()),
                ArchiveExtractionProgress.NOOP
        );

        assertThat(output.resolve("docs/readme.txt")).hasContent("hello");
        assertThat(output.resolve("root.txt")).hasContent("root");
    }

    @Test
    void rejectsManifestTreesThatExceedTheConfiguredNodeLimit() throws Exception {
        Path archive = zip("deep.zip", List.of(entry("a/b/c/file.txt", "content")));
        NasProperties properties = new NasProperties();
        properties.getFileTools().setArchiveMaxEntries(3);
        ArchiveService limitedService = new ArchiveService(List.of(new ZipArchiveBackend()), properties);

        ArchiveManifest manifest = limitedService.manifest(archive);

        assertThat(manifest.extractable()).isFalse();
        assertThat(manifest.message()).contains("path entries");
    }

    @Test
    void scansAndExtractsCompressedTarArchives() throws Exception {
        assertTarArchive(
                tarArchive("bundle.tar.gz", "from gzip", GZIPOutputStream::new),
                ArchiveFormat.TAR_GZIP,
                "gzip-output",
                "from gzip"
        );
        assertTarArchive(
                tarArchive("bundle.tar.bz2", "from bzip2", BZip2CompressorOutputStream::new),
                ArchiveFormat.TAR_BZIP2,
                "bzip2-output",
                "from bzip2"
        );
        assertTarArchive(
                tarArchive("bundle.tar.xz", "from xz", XZCompressorOutputStream::new),
                ArchiveFormat.TAR_XZ,
                "xz-output",
                "from xz"
        );
    }

    @Test
    void scansAndExtractsSevenZipArchives() throws Exception {
        Path archive = sevenZip("bundle.7z", "docs/readme.txt", "from seven zip");
        ArchiveManifest manifest = archiveService.manifest(archive);
        Path output = Files.createDirectory(temporaryDirectory.resolve("seven-output"));
        NasProperties properties = new NasProperties();

        new SevenZipArchiveBackend().extract(
                archive,
                output,
                ArchiveFormat.SEVEN_ZIP,
                ArchiveLimits.from(properties.getFileTools()),
                ArchiveExtractionProgress.NOOP
        );

        assertThat(manifest.extractable()).isTrue();
        assertThat(manifest.children("docs")).extracting(ArchiveEntryInfo::name)
                .containsExactly("readme.txt");
        assertThat(output.resolve("docs/readme.txt")).hasContent("from seven zip");
    }

    private Path zip(String name, List<TestEntry> entries) throws IOException {
        Path archive = temporaryDirectory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (TestEntry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name()));
                output.write(entry.content().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private void markZipEntriesEncrypted(Path archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        for (int index = 0; index <= bytes.length - 10; index++) {
            if (hasSignature(bytes, index, 0x50, 0x4B, 0x03, 0x04)) {
                bytes[index + 6] |= 0x01;
            } else if (hasSignature(bytes, index, 0x50, 0x4B, 0x01, 0x02)) {
                bytes[index + 8] |= 0x01;
            }
        }
        Files.write(archive, bytes);
    }

    private boolean hasSignature(byte[] bytes, int offset, int first, int second, int third, int fourth) {
        return Byte.toUnsignedInt(bytes[offset]) == first
                && Byte.toUnsignedInt(bytes[offset + 1]) == second
                && Byte.toUnsignedInt(bytes[offset + 2]) == third
                && Byte.toUnsignedInt(bytes[offset + 3]) == fourth;
    }

    private Path tarArchive(String name, String content, OutputWrapper compressor) throws IOException {
        Path archive = temporaryDirectory.resolve(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (OutputStream file = Files.newOutputStream(archive);
             OutputStream compressed = compressor.wrap(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
            TarArchiveEntry entry = new TarArchiveEntry("docs/readme.txt");
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        return archive;
    }

    private void assertTarArchive(Path archive, ArchiveFormat format, String outputName, String expectedContent)
            throws IOException {
        ArchiveManifest manifest = archiveService.manifest(archive);
        Path output = Files.createDirectory(temporaryDirectory.resolve(outputName));
        NasProperties properties = new NasProperties();
        new TarArchiveBackend().extract(
                archive,
                output,
                format,
                ArchiveLimits.from(properties.getFileTools()),
                ArchiveExtractionProgress.NOOP
        );

        assertThat(manifest.extractable()).isTrue();
        assertThat(manifest.children("docs")).extracting(ArchiveEntryInfo::name)
                .containsExactly("readme.txt");
        assertThat(output.resolve("docs/readme.txt")).hasContent(expectedContent);
    }

    private Path sevenZip(String name, String entryName, String content) throws IOException {
        Path archive = temporaryDirectory.resolve(name);
        Path source = temporaryDirectory.resolve("seven-source.txt");
        Files.writeString(source, content, StandardCharsets.UTF_8);
        try (SevenZOutputFile output = new SevenZOutputFile(archive.toFile())) {
            SevenZArchiveEntry entry = output.createArchiveEntry(source, entryName);
            output.putArchiveEntry(entry);
            output.write(source);
            output.closeArchiveEntry();
        }
        return archive;
    }

    private TestEntry entry(String name, String content) {
        return new TestEntry(name, content);
    }

    private record TestEntry(String name, String content) {
    }

    @FunctionalInterface
    private interface OutputWrapper {
        OutputStream wrap(OutputStream output) throws IOException;
    }
}
