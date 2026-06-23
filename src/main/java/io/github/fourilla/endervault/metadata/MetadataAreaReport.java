package io.github.fourilla.endervault.metadata;

import java.util.List;

public record MetadataAreaReport(
        MetadataArea area,
        List<MetadataIssue> issues
) {
    public int issueCount() {
        return issues.size();
    }

    public int repairableCount() {
        return (int) issues.stream().filter(MetadataIssue::repairable).count();
    }

    public boolean healthy() {
        return issues.isEmpty();
    }
}
