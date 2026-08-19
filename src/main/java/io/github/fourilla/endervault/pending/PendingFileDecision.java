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
        TargetSnapshot targetSnapshot
) {

    public record TargetSnapshot(long size, Instant modifiedAt) {
    }
}
