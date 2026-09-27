package io.github.fourilla.endervault.directorytransfer;

import java.util.Objects;
import java.util.UUID;

public record DirectoryTransferCompletion(String itemId, Phase phase, String detail) {
    public enum Phase { PREPARED, SOURCE_REMOVED, METADATA_APPLIED, COMPLETE, RETAINED, NEEDS_REVIEW }

    public DirectoryTransferCompletion {
        if (itemId == null || !UUID.fromString(itemId).toString().equals(itemId)) throw new IllegalArgumentException("Invalid merge completion ID.");
        Objects.requireNonNull(phase);
    }
}
