package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

final class DirectoryMergeIndex {
    private final DirectoryMergePlan plan;
    private final Map<String, DirectoryMergePlan.Item> byPath = new HashMap<>();
    private final Map<String, DirectoryMergePlan.Item> byId = new HashMap<>();
    private final Map<String, List<DirectoryMergePlan.Item>> children = new HashMap<>();

    DirectoryMergeIndex(DirectoryMergePlan plan) {
        this.plan = plan;
        for (var item : plan.items()) { byPath.put(item.relativePath(), item); byId.put(item.id(), item); }
        for (var item : plan.items()) {
            var parent = parent(item);
            if (parent != null) children.computeIfAbsent(parent.id(), ignored -> new ArrayList<>()).add(item);
        }
    }

    DirectoryMergePlan.Item item(String id) {
        var item = byId.get(id);
        if (item == null) throw new StorageAccessException("Unknown directory merge item.");
        return item;
    }

    DirectoryMergePlan.Item parent(DirectoryMergePlan.Item item) {
        if (item.relativePath().isEmpty()) return null;
        int slash = item.relativePath().lastIndexOf('/');
        return byPath.get(slash < 0 ? "" : item.relativePath().substring(0, slash));
    }

    List<DirectoryMergePlan.Item> children(DirectoryMergePlan.Item item) {
        return children.getOrDefault(item.id(), List.of());
    }

    String targetPath(DirectoryMergePlan.Item item, Map<String, DirectoryMergeResult> results) {
        var parent = parent(item);
        if (parent == null) return plan.destinationPath();
        var result = results.get(parent.id());
        if (result == null || result.status() != DirectoryMergeResult.Status.PUBLISHED) throw new StorageAccessException("Merge parent is unavailable.");
        return result.targetPath() + "/" + item.relativePath().substring(item.relativePath().lastIndexOf('/') + 1);
    }
}
