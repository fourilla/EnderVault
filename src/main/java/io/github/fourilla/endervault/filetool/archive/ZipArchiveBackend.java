package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
final class ZipArchiveBackend implements ArchiveBackend {

    @Override
    public Set<ArchiveFormat> formats() {
        return Set.of(ArchiveFormat.ZIP);
    }

    @Override
    public ArchiveManifest scan(Path archive, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        ArchiveManifestBuilder manifest = new ArchiveManifestBuilder(format, limits);
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                manifest.add(
                        entry.getName(),
                        entry.isDirectory(),
                        entry.getSize(),
                        entry.getCompressedSize(),
                        unsupportedReason(zip, entry)
                );
            }
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("This ZIP archive could not be opened.", ex);
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
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String unsupported = unsupportedReason(zip, entry);
                if (entry.isDirectory()) {
                    guard.createDirectory(entry.getName(), unsupported);
                } else {
                    if (unsupported != null) {
                        throw new StorageAccessException(unsupported);
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        guard.copyFile(
                                entry.getName(),
                                entry.getSize(),
                                entry.getCompressedSize(),
                                unsupported,
                                input
                        );
                    }
                }
            }
        }
    }

    private String unsupportedReason(ZipFile zip, ZipArchiveEntry entry) {
        if (entry.isUnixSymlink()) {
            return "Symbolic links in archives are not supported.";
        }
        if (!zip.canReadEntryData(entry)) {
            return "The ZIP contains encrypted or unsupported entries.";
        }
        return null;
    }
}
