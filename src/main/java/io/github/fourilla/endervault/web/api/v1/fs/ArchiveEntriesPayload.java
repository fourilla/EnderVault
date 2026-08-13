package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.filetool.archive.ArchiveManifest;
import java.util.List;

public record ArchiveEntriesPayload(
        String parentPath,
        String format,
        int fileCount,
        int directoryCount,
        String totalSizeLabel,
        boolean extractable,
        String message,
        List<ArchiveEntryPayload> entries
) {
    static ArchiveEntriesPayload from(ArchiveManifest manifest, String parentPath) {
        return new ArchiveEntriesPayload(
                manifest.normalizedParent(parentPath),
                manifest.format().label(),
                manifest.fileCount(),
                manifest.directoryCount(),
                manifest.totalSizeLabel(),
                manifest.extractable(),
                manifest.message(),
                manifest.children(parentPath).stream().map(ArchiveEntryPayload::from).toList()
        );
    }
}
