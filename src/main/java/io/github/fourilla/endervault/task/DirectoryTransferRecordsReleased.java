package io.github.fourilla.endervault.task;

import java.util.Set;

/** The caller has finished consuming these durable transfer results. */
public record DirectoryTransferRecordsReleased(Set<String> ids) {
    public DirectoryTransferRecordsReleased { ids = Set.copyOf(ids); }
}
