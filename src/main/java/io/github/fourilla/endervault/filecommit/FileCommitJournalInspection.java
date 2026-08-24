package io.github.fourilla.endervault.filecommit;

import java.time.Instant;

public record FileCommitJournalInspection(
        String operationId,
        FileCommitJournalEntry entry,
        Instant observedAt,
        String failureType
) {

    public FileCommitJournalInspection {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("File commit operation id is required.");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("File commit journal observation time is required.");
        }
        if (entry == null && (failureType == null || failureType.isBlank())) {
            throw new IllegalArgumentException("Unreadable file commit journals require a failure type.");
        }
        if (entry != null && failureType != null) {
            throw new IllegalArgumentException("Readable file commit journals cannot have a failure type.");
        }
    }

    public static FileCommitJournalInspection readable(FileCommitJournalEntry entry) {
        return new FileCommitJournalInspection(
                entry.manifest().operationId(),
                entry,
                entry.state().updatedAt(),
                null
        );
    }

    public static FileCommitJournalInspection unreadable(
            String operationId,
            Instant observedAt,
            Throwable failure
    ) {
        return new FileCommitJournalInspection(
                operationId,
                null,
                observedAt,
                failure == null ? "UnknownFailure" : failure.getClass().getSimpleName()
        );
    }

    public boolean readable() {
        return entry != null;
    }
}
