package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.config.NasProperties;

public record ArchiveLimits(
        int maxEntries,
        long maxEntryBytes,
        long maxTotalBytes,
        int maxCompressionRatio,
        int maxMemoryKiB
) {
    public static ArchiveLimits from(NasProperties.FileTools properties) {
        return new ArchiveLimits(
                Math.max(1, properties.getArchiveMaxEntries()),
                Math.max(1024L, properties.getArchiveEntryMaxBytes()),
                Math.max(1024L, properties.getArchiveTotalMaxBytes()),
                Math.max(1, properties.getArchiveMaxCompressionRatio()),
                Math.max(1024, properties.getArchiveMaxMemoryKiB())
        );
    }
}
