package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.FileNameExtensions;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.search.SearchSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class StorageSearchSchema {

    private static final SearchSchema<Candidate> SCHEMA = new SearchSchema<>(
            (candidate, term) -> candidate.name().toLowerCase(Locale.ROOT).contains(term),
            List.of(
                    SearchSchema.text("name", "Name", Candidate::name),
                    SearchSchema.path("path", "Path", Candidate::relativePath),
                    SearchSchema.enumeration("type", "Entry type", List.of("file", "directory"),
                            candidate -> candidate.directory ? "directory" : "file"),
                    SearchSchema.dateTime("modified", "Modified", ZoneId.systemDefault(), Candidate::modifiedAt),
                    SearchSchema.byteSize("size", "File size", Candidate::size),
                    SearchSchema.exactText("extension", "File extension", Candidate::extension)
            ));

    private StorageSearchSchema() {}

    public static List<String> defaultFields() {
        return List.of("name");
    }

    public static List<SearchSchema.FieldInfo> fields() {
        return SCHEMA.fields();
    }

    static Predicate<Candidate> compile(String query) {
        return SCHEMA.compile(query);
    }

    static final class Candidate {
        private final Path file;
        private final String relativePath;
        private final boolean directory;
        private BasicFileAttributes attributes;

        Candidate(Path file, String relativePath, boolean directory) {
            this.file = file;
            this.relativePath = relativePath;
            this.directory = directory;
        }

        private String name() {
            return file.getFileName().toString();
        }

        private String relativePath() {
            return relativePath;
        }

        private String extension() {
            return directory ? null : FileNameExtensions.extension(name());
        }

        private Long size() {
            if (directory) return null;
            BasicFileAttributes value = attributes();
            return value.isRegularFile() ? value.size() : null;
        }

        private Instant modifiedAt() {
            return attributes().lastModifiedTime().toInstant();
        }

        // Read once only when a size/modified condition reaches this candidate.
        private BasicFileAttributes attributes() {
            if (attributes == null) {
                try {
                    attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException ex) {
                    throw new StorageAccessException("Failed to read file search metadata.", ex);
                }
            }
            return attributes;
        }
    }
}
