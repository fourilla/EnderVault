package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.Locale;

public enum UploaderNamePolicy {
    NONE("Do not ask"),
    OPTIONAL("Optional"),
    REQUIRED("Required");

    private final String label;

    UploaderNamePolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static UploaderNamePolicy from(String value) {
        if (value == null || value.isBlank()) {
            return OPTIONAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Uploader name policy is invalid.", ex);
        }
    }
}
