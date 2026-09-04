package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.storage.FileItem;
import java.time.Instant;

public record StorageEntryPayload(
        String name,
        String path,
        String type,
        long size,
        String sizeLabel,
        Instant modifiedAt,
        String modifiedLabel,
        String mediaType,
        boolean previewable,
        boolean hidden
) {

    static StorageEntryPayload from(FileItem item) {
        return new StorageEntryPayload(
                item.name(),
                item.path(),
                item.directory() ? "directory" : "file",
                item.size(),
                item.sizeLabel(),
                item.modifiedAt(),
                item.modifiedLabel(),
                item.mediaType(),
                item.previewable(),
                item.hidden()
        );
    }
}
