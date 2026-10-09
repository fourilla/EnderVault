package io.github.fourilla.endervault.directorytransfer;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable evidence for one whole-tree move; never a file publication journal. */
public record DirectoryTransferMoveRecord(String id, @JsonProperty(required = true) long revision, String sourcePath, String targetPath,
        DirectoryTransferPlan.Snapshot sourceParent, DirectoryTransferPlan.Snapshot targetParent,
        String treeFingerprint, Phase phase, String detail, Map<String, String> entryKeys) {
    public enum Phase { INTENT, RENAMED, METADATA_APPLIED, COMPLETE, NEEDS_REVIEW, FALLBACK }

    public DirectoryTransferMoveRecord(String id, long revision, String sourcePath, String targetPath,
            DirectoryTransferPlan.Snapshot sourceParent, DirectoryTransferPlan.Snapshot targetParent,
            String treeFingerprint, Phase phase, String detail) {
        this(id, revision, sourcePath, targetPath, sourceParent, targetParent, treeFingerprint, phase, detail, Map.of());
    }

    public DirectoryTransferMoveRecord {
        if (id == null || !UUID.fromString(id).toString().equals(id) || revision < 0) {
            throw new IllegalArgumentException("Invalid directory move identity.");
        }
        requirePath(sourcePath);
        requirePath(targetPath);
        if (sourcePath.equals(targetPath) || sourceParent == null || targetParent == null
                || sourceParent.kind() != DirectoryTransferPlan.Kind.DIRECTORY
                || targetParent.kind() != DirectoryTransferPlan.Kind.DIRECTORY
                || treeFingerprint == null || !treeFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid directory move evidence.");
        }
        Objects.requireNonNull(phase);
        entryKeys = entryKeys == null ? Map.of() : Map.copyOf(entryKeys);
        if (entryKeys.size() > DirectoryTransferPlanner.MAX_ENTRIES) {
            throw new IllegalArgumentException("Directory move identity set is too large.");
        }
        for (var entry : entryKeys.entrySet()) {
            if (!entry.getKey().isEmpty()) requirePath(entry.getKey());
            if (entry.getValue().isBlank()) throw new IllegalArgumentException("Invalid directory move entry identity.");
        }
    }

    /** Length-prefixed UTF-8 fields avoid ambiguous concatenations and plan ordering differences. */
    public static String fingerprint(DirectoryTransferPlan plan) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeInt(plan.items().size());
                for (var item : plan.items().stream().sorted(Comparator.comparing(DirectoryTransferPlan.Item::relativePath)).toList()) {
                    write(out, item.relativePath());
                    var snapshot = item.source();
                    write(out, snapshot.kind().name());
                    out.writeLong(snapshot.size());
                    write(out, snapshot.modifiedAt().toString());
                    write(out, snapshot.fileKey());
                    write(out, snapshot.createdAt().toString());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Could not fingerprint directory move approval.", ex);
        }
    }

    boolean sameIntent(DirectoryTransferMoveRecord other) {
        return other != null && id.equals(other.id) && revision == other.revision
                && sourcePath.equals(other.sourcePath) && targetPath.equals(other.targetPath)
                && sourceParent.equals(other.sourceParent) && targetParent.equals(other.targetParent)
                && treeFingerprint.equals(other.treeFingerprint) && entryKeys.equals(other.entryKeys);
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void requirePath(String path) {
        if (path == null || path.isBlank() || path.contains("\\") || path.contains(":")) {
            throw new IllegalArgumentException("Invalid directory move path.");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Invalid directory move path.");
            }
        }
    }
}
