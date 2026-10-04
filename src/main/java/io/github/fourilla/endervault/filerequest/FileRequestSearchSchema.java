package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class FileRequestSearchSchema {

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (item, term) -> contains(item.request().title(), term) || contains(item.destination(), term),
            List.of(
                    SearchSchema.text("name", "Request title", item -> item.request().title()),
                    SearchSchema.text("description", "Description", item -> item.request().description()),
                    SearchSchema.path("destination", "Destination", Candidate::destination),
                    SearchSchema.enumeration("status", "Link status", List.of("active", "revoked", "expired", "full"),
                            item -> item.request().statusKey(item.now())),
                    SearchSchema.enumeration("uploader-policy", "Uploader name policy",
                            Arrays.stream(UploaderNamePolicy.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
                            item -> item.request().uploaderNamePolicy().name()),
                    SearchSchema.dateTime("created", "Created", ZoneId.systemDefault(), item -> item.request().createdAt()),
                    SearchSchema.dateTime("expires", "Expires", ZoneId.systemDefault(), item -> item.request().expiresAt())
            ));

    private FileRequestSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "destination");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<FileRequest> compile(String query, Instant now) {
        var filter = SCHEMA.compile(query);
        return request -> filter.test(new Candidate(request, now));
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private record Candidate(FileRequest request, Instant now) {
        String destination() {
            String path = request.destinationPath();
            return path == null || path.isBlank() ? "/" : "/" + path;
        }
    }
}
