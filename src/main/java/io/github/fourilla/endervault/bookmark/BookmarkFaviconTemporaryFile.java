package io.github.fourilla.endervault.bookmark;

import java.time.Instant;

public record BookmarkFaviconTemporaryFile(
        String fileName,
        long size,
        String sizeLabel,
        Instant modifiedAt,
        String modifiedLabel,
        boolean active,
        String activeOperation
) implements Comparable<BookmarkFaviconTemporaryFile> {

    @Override
    public int compareTo(BookmarkFaviconTemporaryFile other) {
        return fileName.compareToIgnoreCase(other.fileName);
    }
}
