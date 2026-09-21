package io.github.fourilla.endervault.directorytransfer;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Only this record authorizes startup resumption; a saved review alone does not. */
public record DirectoryTransferRun(String id, @JsonProperty(required = true) long revision,
        Phase phase, @JsonProperty(required = true) boolean paused) {
    public enum Phase { PUBLISHING, FINALIZING, OWNER_COMPLETING, COMPLETE, NEEDS_REVIEW, ABANDONING, ABANDONED }

    public DirectoryTransferRun {
        if (id == null || !UUID.fromString(id).toString().equals(id) || revision < 0 || phase == null) {
            throw new IllegalArgumentException("Invalid directory merge run.");
        }
    }

    public boolean terminal() { return phase == Phase.COMPLETE || phase == Phase.NEEDS_REVIEW || phase == Phase.ABANDONED; }
}
