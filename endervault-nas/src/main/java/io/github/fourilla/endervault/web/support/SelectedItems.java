package io.github.fourilla.endervault.web.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

public final class SelectedItems {

    private static final String PARAMETER_NAME = "items";

    private SelectedItems() {
    }

    public static List<String> from(HttpServletRequest request) {
        String[] values = request.getParameterValues(PARAMETER_NAME);
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
