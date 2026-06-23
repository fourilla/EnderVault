package io.github.fourilla.endervault.metadata;

import java.util.List;

public record MetadataRepairSummary(
        int repaired,
        int failed,
        List<String> messages,
        List<String> repairedTokens
) {
    public MetadataRepairSummary {
        messages = messages == null ? List.of() : List.copyOf(messages);
        repairedTokens = repairedTokens == null ? List.of() : List.copyOf(repairedTokens);
    }

    public boolean anyRepaired() {
        return !repairedTokens.isEmpty();
    }
}
