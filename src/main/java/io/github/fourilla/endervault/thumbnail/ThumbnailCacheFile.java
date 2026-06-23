package io.github.fourilla.endervault.thumbnail;

import java.time.Instant;

public record ThumbnailCacheFile(
        String relativePath,
        long size,
        String sizeLabel,
        Instant modifiedAt,
        String modifiedLabel
) implements Comparable<ThumbnailCacheFile> {

    @Override
    public int compareTo(ThumbnailCacheFile other) {
        return relativePath.compareToIgnoreCase(other.relativePath);
    }
}
