package io.github.fourilla.endervault.filetool.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.stereotype.Component;

@Component
final class TarArchiveBackend implements ArchiveBackend {

    private static final Set<ArchiveFormat> FORMATS = Set.of(
            ArchiveFormat.TAR,
            ArchiveFormat.TAR_GZIP,
            ArchiveFormat.TAR_BZIP2,
            ArchiveFormat.TAR_XZ
    );

    @Override
    public Set<ArchiveFormat> formats() {
        return FORMATS;
    }

    @Override
    public ArchiveManifest scan(Path archive, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        ArchiveManifestBuilder manifest = new ArchiveManifestBuilder(format, limits);
        try (TarArchiveInputStream tar = open(archive, format, limits)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                manifest.add(
                        entry.getName(),
                        entry.isDirectory(),
                        entry.getSize(),
                        -1L,
                        unsupportedReason(entry)
                );
                if (manifest.shouldStopScanning()) {
                    break;
                }
            }
        }
        return manifest.build();
    }

    @Override
    public void extract(
            Path archive,
            Path outputRoot,
            ArchiveFormat format,
            ArchiveLimits limits,
            ArchiveExtractionProgress progress
    ) throws IOException {
        ArchiveExtractionGuard guard = new ArchiveExtractionGuard(outputRoot, limits, progress);
        try (TarArchiveInputStream tar = open(archive, format, limits)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String unsupported = unsupportedReason(entry);
                if (entry.isDirectory()) {
                    guard.createDirectory(entry.getName(), unsupported);
                } else {
                    guard.copyFile(entry.getName(), entry.getSize(), -1L, unsupported, tar);
                }
            }
        }
    }

    private TarArchiveInputStream open(Path archive, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        InputStream fileInput = new BufferedInputStream(Files.newInputStream(archive));
        try {
            InputStream content = switch (format) {
                case TAR -> fileInput;
                case TAR_GZIP -> new GzipCompressorInputStream(fileInput);
                case TAR_BZIP2 -> new BZip2CompressorInputStream(fileInput);
                case TAR_XZ -> XZCompressorInputStream.builder()
                        .setInputStream(fileInput)
                        .setDecompressConcatenated(false)
                        .setMemoryLimitKiB(limits.maxMemoryKiB())
                        .get();
                default -> throw new IllegalArgumentException("Unsupported TAR format: " + format);
            };
            return new TarArchiveInputStream(content);
        } catch (IOException | RuntimeException ex) {
            fileInput.close();
            throw ex;
        }
    }

    private String unsupportedReason(TarArchiveEntry entry) {
        if (entry.isSymbolicLink() || entry.isLink()) {
            return "Links in TAR archives are not supported.";
        }
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
            return "Special device entries in TAR archives are not supported.";
        }
        if (!entry.isDirectory() && !entry.isFile()) {
            return "This TAR entry type is not supported.";
        }
        return null;
    }
}
