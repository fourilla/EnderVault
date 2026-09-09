package io.github.fourilla.endervault.directorymerge;

import java.util.Objects;
import java.util.UUID;

/** Publication is not source cleanup or lifecycle completion. */
public record DirectoryMergeResult(String itemId, Status status, String targetPath,
        DirectoryMergePlan.Snapshot target, String commitId, String detail) {
    public enum Status { PUBLISHED, SKIPPED, NEEDS_REVIEW }

    public DirectoryMergeResult {
        if (itemId == null || !UUID.fromString(itemId).toString().equals(itemId)) {
            throw new IllegalArgumentException("Invalid merge result item.");
        }
        Objects.requireNonNull(status);
        if (status == Status.PUBLISHED) {
            if (targetPath == null || targetPath.isBlank() || target == null) {
                throw new IllegalArgumentException("Published merge result requires a target.");
            }
            if (target.kind() == DirectoryMergePlan.Kind.FILE && commitId == null) {
                throw new IllegalArgumentException("Published file result requires a journal ID.");
            }
            if (targetPath.contains("\\") || targetPath.contains(":")) throw new IllegalArgumentException("Invalid merge target.");
            for (String part : targetPath.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid merge target.");
            }
        } else if (targetPath != null || target != null || commitId != null) {
            throw new IllegalArgumentException("Unpublished merge result cannot claim a target.");
        }
        if (commitId != null && !UUID.fromString(commitId).toString().equals(commitId)) {
            throw new IllegalArgumentException("Invalid merge commit ID.");
        }
    }
}
