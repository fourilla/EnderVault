package io.github.fourilla.endervault.thumbnail;

public record ThumbnailCacheStats(
        boolean videoEnabled,
        boolean comicEnabled,
        long cachedFiles,
        long sizeBytes,
        String sizeLabel,
        int inProgressCount
) {
}
