package io.github.fourilla.endervault.stickynote;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class StickyNoteSearchSchema {

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (item, condition) -> condition.test(item.summary()) || condition.test(item.targetLabel())
                    || condition.test(item.note().context().targetType().name())
                    || condition.test(item.note().context().surface().label()),
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
                    SearchSchema.dateTime("updated", "Updated", ZoneId.systemDefault(), item -> item.note().updatedAt()),
                    SearchSchema.enumeration("status", "Target status", List.of("available", "orphan"),
                            item -> item.targetExists() ? "available" : "orphan")
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

    public record Candidate(StickyNote note, String summary, String targetLabel, BooleanSupplier targetLookup) {
        public boolean targetExists() { return targetLookup.getAsBoolean(); }
    }
}
