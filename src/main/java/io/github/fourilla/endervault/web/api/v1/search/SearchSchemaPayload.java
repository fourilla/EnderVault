package io.github.fourilla.endervault.web.api.v1.search;

import io.github.fourilla.endervault.search.SearchSchema;
import java.util.List;

public record SearchSchemaPayload(
        String scope,
        List<String> defaultFields,
        String defaultOperator,
        List<SearchSchema.FieldInfo> fields,
        SearchSchema.QueryLimits limits
) {
    public SearchSchemaPayload {
        defaultFields = List.copyOf(defaultFields);
        fields = List.copyOf(fields);
    }
}
