package io.github.fourilla.endervault.pending;

import java.time.Instant;

public record PendingFileDecision(
        String id,
        PendingFileDecisionSource source,
        String stagingFilename,
        String destinationPath,
        String originalFilename,
        long size,
        Instant createdAt,
        TargetSnapshot targetSnapshot,
        String sourceReference,
        String submittedBy,
        boolean directory
) {

    public PendingFileDecision(String id, PendingFileDecisionSource source, String stagingFilename,
            String destinationPath, String originalFilename, long size, Instant createdAt,
            TargetSnapshot targetSnapshot, String sourceReference, String submittedBy) {
        this(id, source, stagingFilename, destinationPath, originalFilename, size, createdAt,
                targetSnapshot, sourceReference, submittedBy, false);
    }

    public record TargetSnapshot(long size, Instant modifiedAt) {
    }
}
