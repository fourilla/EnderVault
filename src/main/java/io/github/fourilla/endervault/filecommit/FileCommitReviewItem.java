package io.github.fourilla.endervault.filecommit;

import java.time.Instant;

public record FileCommitReviewItem(
        String operationId,
        FileCommitReviewKind kind,
        FileCommitOwnerType ownerType,
        FileCommitOperationType operationType,
        Instant createdAt,
        Instant updatedAt,
        String detail
) {
}
