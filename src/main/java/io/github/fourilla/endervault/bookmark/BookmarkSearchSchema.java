package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Predicate;

public final class BookmarkSearchSchema {

    private static final SearchSchema<BookmarkItem> SCHEMA = new SearchSchema<>(
            (item, condition) -> condition.test(item.title()) || condition.test(item.url()),
            List.of(
                    SearchSchema.text("name", "Title", BookmarkItem::title),
                    SearchSchema.text("url", "URL", BookmarkItem::url),
                    SearchSchema.text("note", "Note", BookmarkItem::note),
                    SearchSchema.enumeration("type", "Entry type", List.of("link", "directory"),
                            item -> item.directory() ? "directory" : "link"),
                    SearchSchema.dateTime("created", "Created", ZoneId.systemDefault(), BookmarkItem::createdAt),
                    SearchSchema.dateTime("updated", "Updated", ZoneId.systemDefault(), BookmarkItem::updatedAt),
                    SearchSchema.dateTime("accessed", "Last opened", ZoneId.systemDefault(), BookmarkItem::lastOpenedAt)
            ));

    private BookmarkSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "url");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<BookmarkItem> compile(String query) {
        return SCHEMA.compile(query);
    }

}
