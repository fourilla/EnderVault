package io.github.fourilla.endervault.storage;

import java.util.Arrays;

public enum FileSort {
    NAME("name"),
    SIZE("size"),
    MODIFIED("modified"),
    TYPE("type");

    private final String parameter;

    FileSort(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }

    public static FileSort from(String value) {
        if (value == null || value.isBlank()) {
            return NAME;
        }
        return Arrays.stream(values())
                .filter(sort -> sort.parameter.equalsIgnoreCase(value))
                .findFirst()
                .orElse(NAME);
    }
}
