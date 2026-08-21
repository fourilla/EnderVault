package io.github.fourilla.endervault.filecommit;

public enum FileCommitPhase {
    PREPARED,
    COMMITTING,
    FILES_MOVED,
    APPLYING_METADATA,
    NEEDS_REVIEW,
    COMPLETED
}
