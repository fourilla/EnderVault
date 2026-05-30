package io.github.fourilla.endervault.recent;

import java.util.Arrays;

public enum RecentSort {
    RECENT("recent"),
    NAME("name"),
    SIZE("size"),
    MODIFIED("modified"),
    TYPE("type");

    private final String parameter;

    RecentSort(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }

    public static RecentSort from(String value) {
        if (value == null || value.isBlank()) {
            return RECENT;
        }
        return Arrays.stream(values())
                .filter(sort -> sort.parameter.equalsIgnoreCase(value))
                .findFirst()
                .orElse(RECENT);
    }
}
