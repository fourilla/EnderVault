package io.github.fourilla.endervault.directorytransfer;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Durable review state shared by the immediate dialog and later Pending entry. */
public record DirectoryTransferReview(DirectoryTransferPlan plan, @JsonProperty(required = true) long revision, Map<String, Choice> choices) {
    public enum Choice { OVERWRITE, SKIP, KEEP_BOTH, DISCARD_UPLOAD }

    public DirectoryTransferReview {
        if (plan == null || revision < 0) throw new IllegalArgumentException("Invalid directory merge review.");
        choices = Map.copyOf(choices);
        Set<String> ids = plan.items().stream().map(DirectoryTransferPlan.Item::id).collect(Collectors.toSet());
        if (ids.size() != plan.items().size()) throw new IllegalArgumentException("Duplicate directory merge item IDs.");
        if (!ids.containsAll(choices.keySet())) throw new IllegalArgumentException("Unknown directory merge item.");
        for (var item : plan.items()) {
            Choice choice = choices.get(item.id());
            if (choice == null) continue;
            if (!item.requiresDecision()) throw new IllegalArgumentException("Only conflicts accept a choice.");
            if (choice == Choice.OVERWRITE && item.conflict() != DirectoryTransferPlan.Conflict.FILE_CONFLICT) {
                throw new IllegalArgumentException("Type conflicts cannot use file overwrite.");
            }
            if (choice == Choice.SKIP && plan.operation() == DirectoryTransferPlan.Operation.PENDING) {
                throw new IllegalArgumentException("Pending uploads require explicit discard, not skip.");
            }
            if (choice == Choice.DISCARD_UPLOAD && plan.operation() != DirectoryTransferPlan.Operation.PENDING) {
                throw new IllegalArgumentException("Discard upload is not a transfer choice.");
            }
        }
    }

    public boolean fullyReviewed() {
        return plan.items().stream().filter(DirectoryTransferPlan.Item::requiresDecision)
                .allMatch(item -> choices.containsKey(item.id()));
    }
}
