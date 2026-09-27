package io.github.fourilla.endervault.directorytransfer;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;

/** Only this record authorizes startup resumption; a saved review alone does not. */
public record DirectoryTransferRun(String id, @JsonProperty(required = true) long revision,
        Phase phase, @JsonProperty(required = true) boolean paused, boolean recoveryRequired) {
    public DirectoryTransferRun(String id, long revision, Phase phase, boolean paused) {
        this(id, revision, phase, paused, false);
    }

    @JsonCreator
    public static DirectoryTransferRun read(@JsonProperty("id") String id,
            @JsonProperty(value = "revision", required = true) long revision,
            @JsonProperty("phase") Phase phase,
            @JsonProperty(value = "paused", required = true) boolean paused,
            @JsonProperty("recoveryRequired") Boolean recoveryRequired) {
        // This diagnostic flag was absent in existing runs; execution authority stays strict.
        return new DirectoryTransferRun(id, revision, phase, paused, Boolean.TRUE.equals(recoveryRequired));
    }
    public enum Phase { PUBLISHING, FINALIZING, OWNER_COMPLETING, COMPLETE, NEEDS_REVIEW, ABANDONING, ABANDONED }

    public DirectoryTransferRun {
        if (id == null || !UUID.fromString(id).toString().equals(id) || revision < 0 || phase == null) {
            throw new IllegalArgumentException("Invalid directory merge run.");
        }
    }

    public boolean terminal() { return phase == Phase.COMPLETE || phase == Phase.NEEDS_REVIEW || phase == Phase.ABANDONED; }
}
