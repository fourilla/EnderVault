package io.github.fourilla.endervault.thumbnail;

public record ThumbnailCacheStats(
        boolean videoEnabled,
        long cachedFiles,
        long sizeBytes,
        String sizeLabel,
        int inProgressCount
) {
}
