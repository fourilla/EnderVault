package io.github.fourilla.endervault.storage;

import java.nio.file.Path;
import java.util.Objects;

public record ArchiveCommitPlanItem(
        Path stagedPath,
        String targetPath,
        boolean directory
) {

    public ArchiveCommitPlanItem {
        Objects.requireNonNull(stagedPath, "stagedPath");
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("Archive commit target path is required.");
        }
    }
}
