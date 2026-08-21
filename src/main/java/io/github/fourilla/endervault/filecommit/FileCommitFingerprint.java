package io.github.fourilla.endervault.filecommit;

import java.time.Instant;

public record FileCommitFingerprint(long size, Instant modifiedAt, String fileKey) {

    public FileCommitFingerprint {
        if (size < 0L) {
            throw new IllegalArgumentException("File commit fingerprint size cannot be negative.");
        }
        if (fileKey != null && fileKey.isBlank()) {
            fileKey = null;
        }
    }
}
