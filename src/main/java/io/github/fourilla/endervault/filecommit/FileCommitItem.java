package io.github.fourilla.endervault.filecommit;

import java.util.Objects;

public record FileCommitItem(
        int index,
        String stagingPath,
        String targetPath,
        FileCommitFingerprint stagingFingerprint,
        FileCommitFingerprint targetSnapshot
) {

    public FileCommitItem {
        if (index < 0) {
            throw new IllegalArgumentException("File commit item index cannot be negative.");
        }
        stagingPath = requireRelativePath(stagingPath, "stagingPath");
        targetPath = requireRelativePath(targetPath, "targetPath");
        Objects.requireNonNull(stagingFingerprint, "stagingFingerprint");
    }

    private static String requireRelativePath(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("File commit item " + field + " is required.");
        }
        String clean = value.replace('\\', '/').trim();
        if (clean.startsWith("/")
                || clean.matches("^[A-Za-z]:.*")
                || clean.equals("..")
                || clean.startsWith("../")
                || clean.contains("/../")
                || clean.endsWith("/..")) {
            throw new IllegalArgumentException("File commit paths must be storage-relative.");
        }
        return clean;
    }
}
