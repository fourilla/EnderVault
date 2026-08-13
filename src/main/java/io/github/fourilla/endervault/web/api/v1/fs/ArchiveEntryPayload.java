package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.filetool.archive.ArchiveEntryInfo;

public record ArchiveEntryPayload(
        String name,
        String path,
        boolean directory,
        String sizeLabel
) {
    static ArchiveEntryPayload from(ArchiveEntryInfo entry) {
        return new ArchiveEntryPayload(entry.name(), entry.path(), entry.directory(), entry.sizeLabel());
    }
}
