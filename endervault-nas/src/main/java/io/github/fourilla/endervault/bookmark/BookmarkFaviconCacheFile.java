package io.github.fourilla.endervault.bookmark;

import java.time.Instant;

public record BookmarkFaviconCacheFile(
        String fileName,
        long size,
        String sizeLabel,
        Instant modifiedAt,
        String modifiedLabel,
        boolean registered
) implements Comparable<BookmarkFaviconCacheFile> {

    @Override
    public int compareTo(BookmarkFaviconCacheFile other) {
        return fileName.compareToIgnoreCase(other.fileName);
    }
}
