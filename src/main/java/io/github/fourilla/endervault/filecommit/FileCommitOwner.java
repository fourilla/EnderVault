package io.github.fourilla.endervault.filecommit;

import java.util.Objects;

public record FileCommitOwner(FileCommitOwnerType type, String id) {

    public FileCommitOwner {
        Objects.requireNonNull(type, "type");
        id = requireText(id, "id");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("File commit owner " + field + " is required.");
        }
        return value.trim();
    }
}
