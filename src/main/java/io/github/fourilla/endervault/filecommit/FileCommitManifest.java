package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record FileCommitManifest(
        int schemaVersion,
        String operationId,
        FileCommitOwner owner,
        FileCommitOperationType operationType,
        ConflictPolicy conflictPolicy,
        List<FileCommitItem> items,
        Instant createdAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FileCommitManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported file commit journal schema version.");
        }
        operationId = FileCommitJournalPaths.requireOperationId(operationId);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A file commit journal requires at least one item.");
        }
        items = List.copyOf(items);
        HashSet<Integer> indexes = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            FileCommitItem item = Objects.requireNonNull(items.get(index), "items[" + index + "]");
            if (item.index() != index || !indexes.add(item.index())) {
                throw new IllegalArgumentException("File commit item indexes must be contiguous and ordered.");
            }
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
