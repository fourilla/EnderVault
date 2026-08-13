package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.springframework.stereotype.Component;

@Component
final class SevenZipArchiveBackend implements ArchiveBackend {

    @Override
    public Set<ArchiveFormat> formats() {
        return Set.of(ArchiveFormat.SEVEN_ZIP);
    }

    @Override
    public ArchiveManifest scan(Path archive, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        ArchiveManifestBuilder manifest = new ArchiveManifestBuilder(format, limits);
        try (SevenZFile sevenZ = open(archive, limits)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                manifest.add(
                        entry.getName(),
                        entry.isDirectory(),
                        entry.getSize(),
                        -1L,
                        unsupportedReason(entry)
                );
            }
        } catch (PasswordRequiredException ex) {
            throw new StorageAccessException("Password-protected 7-Zip archives are not supported yet.", ex);
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
        try (SevenZFile sevenZ = open(archive, limits)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                String unsupported = unsupportedReason(entry);
                if (entry.isDirectory()) {
                    guard.createDirectory(entry.getName(), unsupported);
                } else {
                    guard.copyFile(
                            entry.getName(),
                            entry.getSize(),
                            -1L,
                            unsupported,
                            sevenZ::read
                    );
                }
            }
        } catch (PasswordRequiredException ex) {
            throw new StorageAccessException("Password-protected 7-Zip archives are not supported yet.", ex);
        }
    }

    private SevenZFile open(Path archive, ArchiveLimits limits) throws IOException {
        return SevenZFile.builder()
                .setPath(archive)
                .setMaxMemoryLimitKiB(limits.maxMemoryKiB())
                .get();
    }

    private String unsupportedReason(SevenZArchiveEntry entry) {
        if (entry.isAntiItem()) {
            return "7-Zip anti-items are not supported.";
        }
        Iterable<? extends SevenZMethodConfiguration> methods = entry.getContentMethods();
        if (methods != null) {
            for (SevenZMethodConfiguration method : methods) {
                if (method.getMethod() == SevenZMethod.AES256SHA256) {
                    return "Password-protected 7-Zip archives are not supported yet.";
                }
            }
        }
        return null;
    }
}
