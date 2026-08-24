package io.github.fourilla.endervault.filecommit;

import java.time.Instant;

public record FileCommitRecoveryIncident(
        String operationId,
        String failureType,
        Instant observedAt
) {
}
