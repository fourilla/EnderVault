package io.github.fourilla.endervault.filetool.text;

import java.time.Instant;
import java.util.UUID;

public record TextDraftRecord(
        UUID id,
        String vaultPath,
        TextSourceFingerprint originalFingerprint,
        Instant createdAt,
        Instant updatedAt,
        String editorToken,
        Instant leaseExpiresAt
) {

    public TextDraftRecord withLease(String nextEditorToken, Instant now, Instant nextLeaseExpiresAt) {
        return new TextDraftRecord(
                id,
                vaultPath,
                originalFingerprint,
                createdAt,
                now,
                nextEditorToken,
                nextLeaseExpiresAt
        );
    }

    public TextDraftRecord withVaultPath(String nextVaultPath) {
        return new TextDraftRecord(
                id,
                nextVaultPath,
                originalFingerprint,
                createdAt,
                updatedAt,
                editorToken,
                leaseExpiresAt
        );
    }
}
