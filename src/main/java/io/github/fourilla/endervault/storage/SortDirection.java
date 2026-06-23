package io.github.fourilla.endervault.storage;

import java.util.Arrays;

public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String parameter;

    SortDirection(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }

    public static SortDirection from(String value) {
        if (value == null || value.isBlank()) {
            return ASC;
        }
        return Arrays.stream(values())
                .filter(direction -> direction.parameter.equalsIgnoreCase(value))
                .findFirst()
                .orElse(ASC);
    }
}
