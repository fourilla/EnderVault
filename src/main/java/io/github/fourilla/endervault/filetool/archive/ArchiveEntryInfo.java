package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.ByteSizeFormatter;

public record ArchiveEntryInfo(
        String path,
        String parentPath,
        String name,
        boolean directory,
        long size,
        boolean virtual
) {
    public String sizeLabel() {
        return directory ? "-" : size < 0 ? "Unknown" : ByteSizeFormatter.humanSize(size);
    }
}
