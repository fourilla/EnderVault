package io.github.fourilla.endervault.filecommit;

import java.time.Instant;

public record FileCommitFingerprint(
        long size,
        Instant modifiedAt,
        String fileKey,
        boolean directory,
        long entryCount,
        String treeDigest
) {

    public FileCommitFingerprint(long size, Instant modifiedAt, String fileKey) {
        this(size, modifiedAt, fileKey, false, 1L, null);
    }

    public FileCommitFingerprint {
        if (size < 0L) {
            throw new IllegalArgumentException("File commit fingerprint size cannot be negative.");
        }
        if (modifiedAt == null) {
            throw new IllegalArgumentException("File commit fingerprint modification time is required.");
        }
        if (fileKey != null && fileKey.isBlank()) {
            fileKey = null;
        }
        if (entryCount < 1L) {
            throw new IllegalArgumentException("File commit fingerprint entry count must be positive.");
        }
        if (treeDigest != null && treeDigest.isBlank()) {
            treeDigest = null;
        }
        if (directory && treeDigest == null) {
            throw new IllegalArgumentException("Directory fingerprints require a tree digest.");
        }
    }
}
