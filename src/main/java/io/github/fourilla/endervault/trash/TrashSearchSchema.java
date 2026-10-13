package io.github.fourilla.endervault.trash;

import io.github.fourilla.endervault.common.FileNameExtensions;
import io.github.fourilla.endervault.search.SearchSchema;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Predicate;

public final class TrashSearchSchema {

    private static final SearchSchema<TrashRecord> SCHEMA = new SearchSchema<>(
            (item, condition) -> condition.test(item.originalName()) || condition.test(item.originalPath()),
            List.of(
                    SearchSchema.text("name", "Original name", TrashRecord::originalName),
                    SearchSchema.path("path", "Original path", TrashRecord::originalPath),
                    SearchSchema.enumeration("type", "Entry type", List.of("file", "directory"),
                            item -> item.directory() ? "directory" : "file"),
                    SearchSchema.exactText("extension", "File extension",
                            item -> item.directory() ? null : FileNameExtensions.extension(item.originalName())),
                    SearchSchema.dateTime("deleted", "Deleted", ZoneId.systemDefault(), TrashRecord::deletedAt),
                    SearchSchema.dateTime("expires", "Expires", ZoneId.systemDefault(), TrashRecord::expiresAt)
            ));

    private TrashSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "path");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<TrashRecord> compile(String query) {
        return SCHEMA.compile(query);
    }
}
