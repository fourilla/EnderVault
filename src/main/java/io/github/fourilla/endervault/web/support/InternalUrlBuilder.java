package io.github.fourilla.endervault.web.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.util.UriComponentsBuilder;

/** Builds internal route URLs from raw values, preserving literal query characters. */
public final class InternalUrlBuilder {

    private static final String QUERY_VARIABLE_PREFIX = "__ev_query_";

    private final UriComponentsBuilder builder;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private int queryVariableIndex;

    private InternalUrlBuilder(String routeTemplate) {
        if (routeTemplate == null || !routeTemplate.startsWith("/") || routeTemplate.startsWith("//")
                || routeTemplate.indexOf('?') >= 0 || routeTemplate.indexOf('#') >= 0
                || routeTemplate.contains("{" + QUERY_VARIABLE_PREFIX)) {
            throw new IllegalArgumentException("An internal route template without a query or fragment is required.");
        }
        builder = UriComponentsBuilder.fromPath(routeTemplate);
    }

    public static InternalUrlBuilder fromPath(String routeTemplate) {
        return new InternalUrlBuilder(routeTemplate);
    }

    /** Adds a fixed route segment or a named path-variable placeholder, never an encoded value. */
    public InternalUrlBuilder pathSegment(String segmentTemplate) {
        Objects.requireNonNull(segmentTemplate, "segmentTemplate");
        if (segmentTemplate.contains("{" + QUERY_VARIABLE_PREFIX)) {
            throw new IllegalArgumentException("Query variable names are reserved.");
        }
        builder.pathSegment(segmentTemplate);
        return this;
    }

    public InternalUrlBuilder pathVariable(String name, Object rawValue) {
        Objects.requireNonNull(name, "name");
        if (name.startsWith(QUERY_VARIABLE_PREFIX)) {
            throw new IllegalArgumentException("Query variable names are reserved.");
        }
        variables.put(name, rawValue);
        return this;
    }

    /** Preserves parameter order and repeated values; callers decide which parameters to omit. */
    public InternalUrlBuilder queryParam(String name, Object... rawValues) {
        Objects.requireNonNull(name, "name");
        if (rawValues == null || rawValues.length == 0) {
            builder.queryParam(name);
            return this;
        }
        for (Object value : rawValues) {
            if (value == null) {
                builder.queryParam(name, (Object) null);
            } else {
                String variable = QUERY_VARIABLE_PREFIX + queryVariableIndex++;
                builder.queryParam(name, "{" + variable + "}");
                variables.put(variable, value);
            }
        }
        return this;
    }

    public String build() {
        // Encoding before expansion treats values as data, including '+' and literal '%2B'.
        return builder.cloneBuilder().encode().buildAndExpand(variables).toUriString();
    }
}
