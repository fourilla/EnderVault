package io.github.fourilla.endervault.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ArchiveCommitPlan(
        Path workspace,
        List<ArchiveCommitPlanItem> items
) {

    public ArchiveCommitPlan {
        Objects.requireNonNull(workspace, "workspace");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An archive commit plan requires at least one item.");
        }
        items = List.copyOf(items);
    }
}
