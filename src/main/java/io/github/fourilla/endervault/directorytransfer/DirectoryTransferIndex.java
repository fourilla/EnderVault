package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

final class DirectoryTransferIndex {
    private final DirectoryTransferPlan plan;
    private final Map<String, DirectoryTransferPlan.Item> byPath = new HashMap<>();
    private final Map<String, DirectoryTransferPlan.Item> byId = new HashMap<>();
    private final Map<String, List<DirectoryTransferPlan.Item>> children = new HashMap<>();

    DirectoryTransferIndex(DirectoryTransferPlan plan) {
        this.plan = plan;
        for (var item : plan.items()) { byPath.put(item.relativePath(), item); byId.put(item.id(), item); }
        for (var item : plan.items()) {
            var parent = parent(item);
            if (parent != null) children.computeIfAbsent(parent.id(), ignored -> new ArrayList<>()).add(item);
        }
    }

    DirectoryTransferPlan.Item item(String id) {
        var item = byId.get(id);
        if (item == null) throw new StorageAccessException("Unknown directory merge item.");
        return item;
    }

    DirectoryTransferPlan.Item parent(DirectoryTransferPlan.Item item) {
        if (item.relativePath().isEmpty()) return null;
        int slash = item.relativePath().lastIndexOf('/');
        return byPath.get(slash < 0 ? "" : item.relativePath().substring(0, slash));
    }

    List<DirectoryTransferPlan.Item> children(DirectoryTransferPlan.Item item) {
        return children.getOrDefault(item.id(), List.of());
    }

    String targetPath(DirectoryTransferPlan.Item item, Map<String, DirectoryTransferResult> results) {
        var parent = parent(item);
        String name = targetName(item);
        if (parent == null) {
            if (!plan.targetNames().containsKey("")) return plan.destinationPath();
            int slash = plan.destinationPath().lastIndexOf('/');
            return (slash < 0 ? "" : plan.destinationPath().substring(0, slash + 1)) + name;
        }
        var result = results.get(parent.id());
        if (result == null || result.status() != DirectoryTransferResult.Status.PUBLISHED) throw new StorageAccessException("Merge parent is unavailable.");
        return result.targetPath() + "/" + name;
    }

    /** Resolve published parent names without claiming unpublished children are committed. */
    String displayTargetPath(DirectoryTransferPlan.Item item, Map<String, DirectoryTransferResult> results) {
        var result = results.get(item.id());
        if (result != null && result.targetPath() != null) return result.targetPath();
        var parent = parent(item);
        return parent == null ? targetPath(item, Map.of()) : displayTargetPath(parent, results) + "/" + targetName(item);
    }

    /** Planned destination, before a new KEEP_BOTH choice allocates its final name. */
    String plannedTargetPath(DirectoryTransferPlan.Item item) {
        var parent = parent(item);
        return parent == null ? targetPath(item, Map.of()) : plannedTargetPath(parent) + "/" + targetName(item);
    }

    private String targetName(DirectoryTransferPlan.Item item) {
        return plan.targetNames().getOrDefault(item.relativePath(),
                item.relativePath().substring(item.relativePath().lastIndexOf('/') + 1));
    }
}
