package io.github.fourilla.endervault.recent;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class RecentSearchSchema {

    private static final SearchSchema<RecentListItem> SCHEMA = new SearchSchema<>(
            (item, term) -> item.name().toLowerCase(Locale.ROOT).contains(term)
                    || item.path().toLowerCase(Locale.ROOT).contains(term),
            List.of(
                    SearchSchema.text("name", "Name", RecentListItem::name),
                    SearchSchema.path("path", "Path", RecentListItem::path),
                    SearchSchema.enumeration("type", "Entry type", List.of("file", "directory"),
                            item -> item.directory() ? "directory" : "file"),
                    SearchSchema.dateTime("modified", "Modified", ZoneId.systemDefault(), RecentListItem::modifiedAt),
                    SearchSchema.dateTime("accessed", "Last accessed", ZoneId.systemDefault(), RecentListItem::lastAccessedAt)
            ));

    private RecentSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "path");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<RecentListItem> compile(String query) {
        return SCHEMA.compile(query);
    }
}
