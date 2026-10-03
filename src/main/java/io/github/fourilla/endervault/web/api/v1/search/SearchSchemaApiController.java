package io.github.fourilla.endervault.web.api.v1.search;

import io.github.fourilla.endervault.search.SearchSchema;
import io.github.fourilla.endervault.storage.StorageSearchSchema;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search/schemas")
public class SearchSchemaApiController {

    @GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchSchemaPayload files() {
        return new SearchSchemaPayload("files", StorageSearchSchema.defaultFields(), "AND",
                StorageSearchSchema.fields(), SearchSchema.limits());
    }
}
