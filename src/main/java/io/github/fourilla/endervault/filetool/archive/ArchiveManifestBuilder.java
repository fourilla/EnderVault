package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ArchiveManifestBuilder {

    private final ArchiveFormat format;
    private final ArchiveLimits limits;
    private final Map<String, ArchiveEntryInfo> entries = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private int sourceEntryCount;
    private int fileCount;
    private int rejectedEntryCount;
    private long totalUncompressedBytes;

    ArchiveManifestBuilder(ArchiveFormat format, ArchiveLimits limits) {
        this.format = format;
        this.limits = limits;
    }

    void add(String rawName, boolean directory, long size, long compressedSize, String unsupportedReason) {
        sourceEntryCount++;
        if (sourceEntryCount > limits.maxEntries()) {
            reject("The archive contains more than %d entries.".formatted(limits.maxEntries()));
            return;
        }

        String path;
        try {
            path = ArchivePathPolicy.normalizeEntry(rawName);
        } catch (StorageAccessException ex) {
            reject(ex.getMessage());
            return;
        }
        if (unsupportedReason != null && !unsupportedReason.isBlank()) {
            reject(unsupportedReason);
            return;
        }
        ArchiveEntryInfo existing = entries.get(path);
        if (existing != null && !(directory && existing.virtual())) {
            reject("The archive contains duplicate normalized paths.");
            return;
        }
        if (!directory) {
            if (size > limits.maxEntryBytes()) {
                reject("An archive entry exceeds the per-file extraction limit.");
                return;
            }
            if (size >= 0 && totalUncompressedBytes > limits.maxTotalBytes() - size) {
                reject("The archive exceeds the total extraction size limit.");
                return;
            }
            if (size > 0 && compressedSize > 0
                    && size / (double) compressedSize > limits.maxCompressionRatio()) {
                reject("An archive entry exceeds the configured compression ratio limit.");
                return;
            }
        }

        if (!addVirtualParents(path)) {
            return;
        }
        if (!directory) {
            if (size >= 0) {
                totalUncompressedBytes += size;
            }
            fileCount++;
        }
        entries.put(path, new ArchiveEntryInfo(
                path,
                ArchivePathPolicy.parent(path),
                ArchivePathPolicy.name(path),
                directory,
                directory ? 0L : size,
                false
        ));
    }

    void reject(String message) {
        rejectedEntryCount++;
        if (problems.size() < 3 && message != null && !message.isBlank() && !problems.contains(message)) {
            problems.add(message);
        }
    }

    boolean rejected() {
        return rejectedEntryCount > 0;
    }

    ArchiveManifest build() {
        int directoryCount = (int) entries.values().stream().filter(ArchiveEntryInfo::directory).count();
        String message = problems.isEmpty()
                ? ""
                : String.join(" ", problems) + (rejectedEntryCount > problems.size() ? " Additional entries were rejected." : "");
        return new ArchiveManifest(
                format,
                List.copyOf(entries.values()),
                sourceEntryCount,
                fileCount,
                directoryCount,
                totalUncompressedBytes,
                rejectedEntryCount,
                rejectedEntryCount == 0,
                message
        );
    }

    private boolean addVirtualParents(String path) {
        String parent = ArchivePathPolicy.parent(path);
        List<String> missing = new ArrayList<>();
        while (!parent.isBlank()) {
            ArchiveEntryInfo existing = entries.get(parent);
            if (existing != null) {
                if (!existing.directory()) {
                    reject("An archive path is used as both a file and a directory.");
                    return false;
                }
                break;
            }
            missing.add(parent);
            parent = ArchivePathPolicy.parent(parent);
        }
        int newLeafEntry = entries.containsKey(path) ? 0 : 1;
        if (entries.size() + missing.size() + newLeafEntry > limits.maxEntries()) {
            reject("The archive expands to more than %d path entries.".formatted(limits.maxEntries()));
            return false;
        }
        for (int index = missing.size() - 1; index >= 0; index--) {
            String virtualPath = missing.get(index);
            entries.put(virtualPath, new ArchiveEntryInfo(
                    virtualPath,
                    ArchivePathPolicy.parent(virtualPath),
                    ArchivePathPolicy.name(virtualPath),
                    true,
                    0L,
                    true
            ));
        }
        return true;
    }
}
