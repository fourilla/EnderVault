package io.github.fourilla.endervault.temporary;

import java.nio.file.Path;
import java.time.Instant;

public record TemporaryArtifact(
        Path path,
        TemporaryArtifactType type,
        String ownerId,
        Instant registeredAt
) {
}
