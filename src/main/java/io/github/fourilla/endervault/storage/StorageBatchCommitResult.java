package io.github.fourilla.endervault.storage;

import java.util.List;

public record StorageBatchCommitResult(
        List<String> committedPaths
) {
    public StorageBatchCommitResult {
        committedPaths = List.copyOf(committedPaths);
    }

    public int committedCount() {
        return committedPaths.size();
    }
}
