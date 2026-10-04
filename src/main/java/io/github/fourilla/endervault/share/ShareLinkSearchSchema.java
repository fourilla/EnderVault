package io.github.fourilla.endervault.share;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class ShareLinkSearchSchema {

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (item, term) -> item.path().toLowerCase(Locale.ROOT).contains(term),
            List.of(
                    SearchSchema.text("name", "Target name", Candidate::name),
                    SearchSchema.path("path", "Target path", Candidate::path),
                    SearchSchema.enumeration("type", "Target type", List.of("file", "directory"),
                            item -> item.link().type().name()),
                    SearchSchema.enumeration("status", "Link status", List.of("active", "revoked", "expired"),
                            item -> item.link().statusClass(item.now())),
                    SearchSchema.enumeration("preview", "Preview", List.of("on", "off"),
                            item -> item.link().previewEnabled() ? "on" : "off"),
                    SearchSchema.dateTime("created", "Created", ZoneId.systemDefault(), item -> item.link().createdAt()),
                    SearchSchema.dateTime("expires", "Expires", ZoneId.systemDefault(), item -> item.link().expiresAt())
            ));

    private ShareLinkSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("path");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<ShareLink> compile(String query, Instant now) {
        var filter = SCHEMA.compile(query);
        return link -> filter.test(new Candidate(link, now));
    }

    private record Candidate(ShareLink link, Instant now) {
        String path() {
            return link.path() == null || link.path().isBlank() ? "/" : "/" + link.path();
        }

        String name() {
            String path = path();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
