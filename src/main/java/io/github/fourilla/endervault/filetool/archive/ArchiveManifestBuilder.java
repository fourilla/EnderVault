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
    private boolean browsable = true;
    private boolean extractable = true;
    private boolean stopScanning;
    private boolean additionalProblems;

    ArchiveManifestBuilder(ArchiveFormat format, ArchiveLimits limits) {
        this.format = format;
        this.limits = limits;
    }

    void add(String rawName, boolean directory, long size, long compressedSize, String unsupportedReason) {
        sourceEntryCount++;
        if (sourceEntryCount > limits.maxEntries()) {
            reject("The archive contains more than %d entries.".formatted(limits.maxEntries()));
            stopScanning = true;
            return;
        }

        String path;
        try {
            path = ArchivePathPolicy.normalizeEntry(rawName);
        } catch (StorageAccessException ex) {
            reject(ex.getMessage());
            return;
        }
        ArchiveEntryInfo existing = entries.get(path);
        if (existing != null && !(directory && existing.virtual())) {
            reject("The archive contains duplicate normalized paths.");
            return;
        }
        if (!addVirtualParents(path)) {
            return;
        }
        if (unsupportedReason != null && !unsupportedReason.isBlank()) {
            blockExtraction(unsupportedReason);
        }
        if (!directory) {
            if (size > limits.maxEntryBytes()) {
                blockExtraction("An archive entry exceeds the per-file extraction limit.");
            }
            if (size >= 0 && totalUncompressedBytes > limits.maxTotalBytes() - size) {
                blockExtraction("The archive exceeds the total extraction size limit.");
            }
            if (size > 0 && compressedSize > 0
                    && size / (double) compressedSize > limits.maxCompressionRatio()) {
                blockExtraction("An archive entry exceeds the configured compression ratio limit.");
            }
        }

        if (!directory) {
            if (size >= 0) {
                totalUncompressedBytes = saturatingAdd(totalUncompressedBytes, size);
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
        blockExtraction(message);
    }

    void markUnreadable(String message) {
        browsable = false;
        extractable = false;
        stopScanning = true;
        entries.clear();
        sourceEntryCount = 0;
        fileCount = 0;
        totalUncompressedBytes = 0L;
        rejectedEntryCount = 0;
        problems.clear();
        additionalProblems = false;
        recordProblem(message);
    }

    boolean shouldStopScanning() {
        return stopScanning;
    }

    ArchiveManifest build() {
        int directoryCount = (int) entries.values().stream().filter(ArchiveEntryInfo::directory).count();
        String message = problems.isEmpty()
                ? ""
                : String.join(" ", problems) + (additionalProblems ? " Additional archive issues were detected." : "");
        return new ArchiveManifest(
                format,
                List.copyOf(entries.values()),
                sourceEntryCount,
                fileCount,
                directoryCount,
                totalUncompressedBytes,
                rejectedEntryCount,
                browsable,
                extractable,
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
            stopScanning = true;
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

    private void blockExtraction(String message) {
        extractable = false;
        recordProblem(message);
    }

    private void recordProblem(String message) {
        if (message == null || message.isBlank() || problems.contains(message)) {
            return;
        }
        if (problems.size() < 3) {
            problems.add(message);
        } else {
            additionalProblems = true;
        }
    }

    private long saturatingAdd(long current, long value) {
        return current > Long.MAX_VALUE - value ? Long.MAX_VALUE : current + value;
    }
}
