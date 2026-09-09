package io.github.fourilla.endervault.directorymerge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.UUID;

/** A read-only snapshot, not permission to overwrite files without execution-time validation. */
public record DirectoryMergePlan(
        String id, Operation operation, String sourceReference, String destinationPath,
        Instant createdAt, List<Item> items
) {
    public DirectoryMergePlan {
        Objects.requireNonNull(id);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(sourceReference);
        Objects.requireNonNull(destinationPath);
        Objects.requireNonNull(createdAt);
        items = List.copyOf(items);
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid plan ID.");
        if (items.isEmpty() || items.size() > DirectoryMergePlanner.MAX_ENTRIES) throw new IllegalArgumentException("Invalid merge entry count.");
        var byPath = new HashMap<String, Item>();
        var byId = new HashMap<String, Item>();
        for (Item item : items) {
            if (byPath.put(item.relativePath(), item) != null || byId.put(item.id(), item) != null) {
                throw new IllegalArgumentException("Duplicate merge entry.");
            }
        }
        if (!byPath.containsKey("") || byPath.get("").source().kind() != Kind.DIRECTORY) {
            throw new IllegalArgumentException("Merge source root is required.");
        }
        for (Item item : items) {
            if (item.relativePath().isEmpty()) continue;
            int slash = item.relativePath().lastIndexOf('/');
            Item parent = byPath.get(slash < 0 ? "" : item.relativePath().substring(0, slash));
            if (parent == null || parent.source().kind() != Kind.DIRECTORY) throw new IllegalArgumentException("Invalid merge parent.");
            String expectedBlock = parent.conflict() == Conflict.TYPE_CONFLICT ? parent.id() : parent.blockedBy();
            if (!Objects.equals(expectedBlock, item.blockedBy())) throw new IllegalArgumentException("Invalid merge conflict parent.");
        }
    }

    public enum Operation { COPY, MOVE, PENDING }
    public enum Kind { FILE, DIRECTORY }
    public enum Conflict { ADD, MERGE, FILE_CONFLICT, TYPE_CONFLICT, BLOCKED_BY_PARENT }

    public record Snapshot(Kind kind, long size, Instant modifiedAt, String fileKey, Instant createdAt) {
        public Snapshot {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(modifiedAt);
            Objects.requireNonNull(createdAt);
            if (size < 0) throw new IllegalArgumentException("Negative entry size.");
        }
    }

    // Relative path is empty only for the top-level directory. IDs, not client paths, select rows.
    public record Item(String id, String relativePath, Snapshot source, Snapshot target,
            Conflict conflict, String blockedBy) {
        public Item {
            Objects.requireNonNull(id);
            Objects.requireNonNull(relativePath);
            Objects.requireNonNull(source);
            Objects.requireNonNull(conflict);
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid merge item ID.");
            if (!relativePath.isEmpty()) {
                if (relativePath.contains("\\") || relativePath.contains(":")) throw new IllegalArgumentException("Invalid relative path.");
                for (String part : relativePath.split("/", -1)) {
                    if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid relative path.");
                }
            }
            boolean valid = switch (conflict) {
                case ADD -> target == null && blockedBy == null;
                case MERGE -> target != null && target.kind() == Kind.DIRECTORY && source.kind() == Kind.DIRECTORY && blockedBy == null;
                case FILE_CONFLICT -> target != null && target.kind() == Kind.FILE && source.kind() == Kind.FILE && blockedBy == null;
                case TYPE_CONFLICT -> target != null && target.kind() != source.kind() && blockedBy == null;
                case BLOCKED_BY_PARENT -> target == null && blockedBy != null && !relativePath.isEmpty();
            };
            if (!valid) throw new IllegalArgumentException("Inconsistent merge conflict.");
        }

        public boolean requiresDecision() {
            return conflict == Conflict.FILE_CONFLICT || conflict == Conflict.TYPE_CONFLICT;
        }
    }
}
