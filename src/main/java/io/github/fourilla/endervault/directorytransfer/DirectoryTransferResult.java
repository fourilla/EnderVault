package io.github.fourilla.endervault.directorytransfer;

import java.util.Objects;
import java.util.UUID;

/** Publication is not source cleanup or lifecycle completion. */
public record DirectoryTransferResult(String itemId, Status status, String targetPath,
        DirectoryTransferPlan.Snapshot target, String commitId, String detail, String renameId) {
    public enum Status { PUBLISHED, MOVED, SKIPPED, NEEDS_REVIEW, DISCARD_APPROVED }

    public DirectoryTransferResult(String itemId, Status status, String targetPath,
            DirectoryTransferPlan.Snapshot target, String commitId, String detail) {
        this(itemId, status, targetPath, target, commitId, detail, null);
    }

    public boolean hasTarget() { return status == Status.PUBLISHED || status == Status.MOVED; }

    public DirectoryTransferResult {
        if (itemId == null || !UUID.fromString(itemId).toString().equals(itemId)) {
            throw new IllegalArgumentException("Invalid merge result item.");
        }
        Objects.requireNonNull(status);
        if (status == Status.PUBLISHED || status == Status.MOVED) {
            if (targetPath == null || targetPath.isBlank() || target == null) {
                throw new IllegalArgumentException("Published merge result requires a target.");
            }
            if (status == Status.PUBLISHED && target.kind() == DirectoryTransferPlan.Kind.FILE && commitId == null) {
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
        if (status == Status.MOVED) {
            if (commitId != null || renameId == null || !UUID.fromString(renameId).toString().equals(renameId)) {
                throw new IllegalArgumentException("Moved result requires a directory move proof, not a publication journal.");
            }
        } else if (renameId != null) {
            throw new IllegalArgumentException("Only a moved result can refer to a directory move proof.");
        }
    }
}
