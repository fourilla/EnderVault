package io.github.fourilla.endervault.filecommit;

import java.util.UUID;

final class FileCommitJournalPaths {

    private FileCommitJournalPaths() {
    }

    static String requireOperationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("File commit operation id is required.");
        }
        String clean = value.trim();
        try {
            UUID parsed = UUID.fromString(clean);
            if (!parsed.toString().equals(clean.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("File commit operation id must be a canonical UUID.");
            }
            return parsed.toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("File commit operation id must be a canonical UUID.", ex);
        }
    }
}
