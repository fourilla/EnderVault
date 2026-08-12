package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.storage.DirectoryListing;
import java.util.List;
import java.util.stream.Stream;

public record StorageEntriesPayload(
        String path,
        String parentPath,
        List<StorageEntryPayload> entries
) {

    static StorageEntriesPayload from(DirectoryListing listing) {
        return new StorageEntriesPayload(
                listing.path(),
                listing.parentPath(),
                Stream.concat(listing.directories().stream(), listing.files().stream())
                        .map(StorageEntryPayload::from)
                        .toList()
        );
    }
}
