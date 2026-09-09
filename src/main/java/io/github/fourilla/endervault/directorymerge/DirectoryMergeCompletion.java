package io.github.fourilla.endervault.directorymerge;

import java.util.Objects;
import java.util.UUID;

public record DirectoryMergeCompletion(String itemId, Phase phase, String detail) {
    public enum Phase { PREPARED, SOURCE_REMOVED, METADATA_APPLIED, COMPLETE, RETAINED, NEEDS_REVIEW }

    public DirectoryMergeCompletion {
        if (itemId == null || !UUID.fromString(itemId).toString().equals(itemId)) throw new IllegalArgumentException("Invalid merge completion ID.");
        Objects.requireNonNull(phase);
    }
}
