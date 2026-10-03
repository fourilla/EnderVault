package io.github.fourilla.endervault.pending;

import io.github.fourilla.endervault.search.SearchSchema;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class PendingDecisionSearchSchema {

    private static final List<String> SOURCES = Stream.concat(
            Arrays.stream(PendingFileDecisionSource.values()).map(value -> value.name().toLowerCase(Locale.ROOT)),
            Stream.of("directory_copy", "directory_move")).toList();

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (item, term) -> contains(item.name(), term) || contains(item.destination(), term),
            List.of(
                    SearchSchema.text("name", "Item name", Candidate::name),
                    SearchSchema.path("destination", "Destination", Candidate::destination),
                    SearchSchema.enumeration("source", "Source", SOURCES, Candidate::source),
                    SearchSchema.enumeration("type", "Entry type", List.of("file", "directory"),
                            item -> item.directory() ? "directory" : "file"),
                    SearchSchema.dateTime("created", "Created", ZoneId.systemDefault(), Candidate::createdAt),
                    SearchSchema.text("submitter", "Uploader name", Candidate::submittedBy)
            ));

    private PendingDecisionSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name", "destination");
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

    public record Candidate(String name, String destination, String source,
            boolean directory, Instant createdAt, String submittedBy) {

        public static Candidate from(PendingFileDecision decision) {
            return new Candidate(decision.originalFilename(), destinationLabel(decision.destinationPath()),
                    decision.source().name(), decision.directory(), decision.createdAt(), decision.submittedBy());
        }
    }

    public static String destinationLabel(String path) {
        return path == null || path.isBlank() ? "/" : "/" + path;
    }
}
