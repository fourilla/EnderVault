package io.github.fourilla.endervault.filecommit;

import java.time.Instant;
import java.util.Objects;

public record FileCommitJournalState(
        String operationId,
        FileCommitPhase phase,
        int nextItemIndex,
        String detail,
        Instant updatedAt
) {

    public FileCommitJournalState {
        operationId = FileCommitJournalPaths.requireOperationId(operationId);
        Objects.requireNonNull(phase, "phase");
        if (nextItemIndex < 0) {
            throw new IllegalArgumentException("File commit next item index cannot be negative.");
        }
        detail = detail == null || detail.isBlank() ? null : detail.trim();
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static FileCommitJournalState prepared(String operationId, Instant now) {
        return new FileCommitJournalState(operationId, FileCommitPhase.PREPARED, 0, null, now);
    }
}
