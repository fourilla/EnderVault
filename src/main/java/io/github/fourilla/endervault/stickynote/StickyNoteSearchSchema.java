package io.github.fourilla.endervault.stickynote;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class StickyNoteSearchSchema {

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (item, term) -> contains(item.summary(), term) || contains(item.targetLabel(), term)
                    || contains(item.note().context().targetType().name(), term)
                    || contains(item.note().context().surface().label(), term),
            List.of(
                    SearchSchema.text("name", "Summary", Candidate::summary),
                    SearchSchema.text("content", "Full content", item -> item.note().content()),
                    SearchSchema.text("target", "Target label", Candidate::targetLabel),
                    SearchSchema.enumeration("type", "Target type",
                            Arrays.stream(StickyNoteTargetType.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
                            item -> item.note().context().targetType().name()),
                    SearchSchema.enumeration("surface", "Surface",
                            Arrays.stream(StickyNoteSurface.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
                            item -> item.note().context().surface().name()),
                    SearchSchema.dateTime("created", "Created", ZoneId.systemDefault(), item -> item.note().createdAt()),
                    SearchSchema.dateTime("updated", "Updated", ZoneId.systemDefault(), item -> item.note().updatedAt())
            ));

    private StickyNoteSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "target", "type", "surface");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    public static Predicate<Candidate> compile(String query) {
        return SCHEMA.compile(query);
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    public record Candidate(StickyNote note, String summary, String targetLabel) {}
}
