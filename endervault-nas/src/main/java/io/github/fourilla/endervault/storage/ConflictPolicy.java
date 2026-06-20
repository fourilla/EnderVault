package io.github.fourilla.endervault.storage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum ConflictPolicy {
    CANCEL("cancel"),
    RENAME("rename"),
    OVERWRITE("overwrite");

    private final String value;

    ConflictPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ConflictPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(policy -> policy.value.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Conflict policy is invalid."));
    }

    public static List<String> valuesForSettings() {
        return Arrays.stream(values()).map(ConflictPolicy::value).toList();
    }
}
