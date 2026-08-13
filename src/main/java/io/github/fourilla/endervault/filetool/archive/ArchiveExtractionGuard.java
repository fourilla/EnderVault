package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ArchiveExtractionGuard {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path outputRoot;
    private final ArchiveLimits limits;
    private final ArchiveExtractionProgress progress;
    private long actualTotalBytes;
    private int entryCount;

    ArchiveExtractionGuard(Path outputRoot, ArchiveLimits limits, ArchiveExtractionProgress progress) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.limits = limits;
        this.progress = progress;
    }

    void createDirectory(String rawName, String unsupportedReason) throws IOException {
        EntryTarget target = prepare(rawName, true, 0L, -1L, unsupportedReason);
        progress.checkCanceled();
        Files.createDirectories(target.path());
        progress.incrementProcessedItems();
    }

    void copyFile(
            String rawName,
            long declaredSize,
            long compressedSize,
            String unsupportedReason,
            InputStream input
    ) throws IOException {
        copyFile(rawName, declaredSize, compressedSize, unsupportedReason, input::read);
    }

    void copyFile(
            String rawName,
            long declaredSize,
            long compressedSize,
            String unsupportedReason,
            ContentReader reader
    ) throws IOException {
        EntryTarget target = prepare(rawName, false, declaredSize, compressedSize, unsupportedReason);
        Path parent = target.path().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Archive contains duplicate output paths.");
        }

        long entryBytes = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target.path(), StandardOpenOption.CREATE_NEW)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                progress.checkCanceled();
                entryBytes = checkedAdd(entryBytes, read, limits.maxEntryBytes(), "Archive entry is too large.");
                actualTotalBytes = checkedAdd(
                        actualTotalBytes,
                        read,
                        limits.maxTotalBytes(),
                        "Archive extraction exceeds the total size limit."
                );
                output.write(buffer, 0, read);
                progress.addProcessedBytes(read);
            }
        }
        progress.incrementProcessedItems();
    }

    private EntryTarget prepare(
            String rawName,
            boolean directory,
            long declaredSize,
            long compressedSize,
            String unsupportedReason
    ) {
        progress.checkCanceled();
        entryCount++;
        if (entryCount > limits.maxEntries()) {
            throw new StorageAccessException("Archive contains too many entries.");
        }
        if (unsupportedReason != null && !unsupportedReason.isBlank()) {
            throw new StorageAccessException(unsupportedReason);
        }
        if (!directory && declaredSize > limits.maxEntryBytes()) {
            throw new StorageAccessException("Archive entry is larger than the configured limit.");
        }
        if (!directory && declaredSize > 0 && compressedSize > 0
                && declaredSize / (double) compressedSize > limits.maxCompressionRatio()) {
            throw new StorageAccessException("Archive entry exceeds the configured compression ratio limit.");
        }
        String normalized = ArchivePathPolicy.normalizeEntry(rawName);
        progress.message("Extracting " + ArchivePathPolicy.name(normalized));
        return new EntryTarget(normalized, ArchivePathPolicy.resolveDestination(outputRoot, normalized));
    }

    private long checkedAdd(long current, int addition, long limit, String message) {
        if (current > limit - addition) {
            throw new StorageAccessException(message);
        }
        return current + addition;
    }

    @FunctionalInterface
    interface ContentReader {
        int read(byte[] buffer) throws IOException;
    }

    private record EntryTarget(String relativePath, Path path) {
    }
}
