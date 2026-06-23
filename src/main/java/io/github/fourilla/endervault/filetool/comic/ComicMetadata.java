package io.github.fourilla.endervault.filetool.comic;

import java.util.List;

public record ComicMetadata(
        boolean present,
        boolean truncated,
        String rawText,
        List<ComicMetadataEntry> entries
) {
    public static ComicMetadata empty() {
        return new ComicMetadata(false, false, "", List.of());
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }
}
