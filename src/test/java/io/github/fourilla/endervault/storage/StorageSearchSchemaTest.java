package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.search.SearchSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageSearchSchemaTest {

    @TempDir
    Path root;

    @Test
    void schemaAndCheapConditionsDoNotReadTheFile() {
        var candidate = new StorageSearchSchema.Candidate(root.resolve("missing-report.txt"), "docs/report.txt", false);

        assertThat(StorageSearchSchema.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "path", "type", "modified", "size", "extension");
        assertThat(StorageSearchSchema.defaultFields()).containsExactly("name");
        assertThat(StorageSearchSchema.compile("report").test(candidate)).isTrue();
        assertThat(StorageSearchSchema.compile("name:REPORT path:docs type:file").test(candidate)).isTrue();
        assertThat(StorageSearchSchema.compile("type:directory").test(candidate)).isFalse();
    }

    @Test
    void dateConditionsOnlyReadMetadataWhenTheirBranchIsEvaluated() {
        var candidate = new StorageSearchSchema.Candidate(root.resolve("missing-report.txt"), "report.txt", false);

        assertThat(StorageSearchSchema.compile("report || modified:2026-10-03").test(candidate)).isTrue();
        assertThat(StorageSearchSchema.compile("name:other modified:2026-10-03").test(candidate)).isFalse();
        assertThatThrownBy(() -> StorageSearchSchema.compile("modified:2026-10-03").test(candidate))
                .isInstanceOf(StorageAccessException.class).hasMessage("Failed to read file search metadata.");
    }

    @Test
    void cachesModifiedTimeWithinOneCandidateButNotAcrossCandidates() throws Exception {
        Path file = root.resolve("report.txt");
        Files.writeString(file, "hello");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));
        var original = new StorageSearchSchema.Candidate(file, "report.txt", false);
        Predicate<StorageSearchSchema.Candidate> filter = StorageSearchSchema.compile(
                "modified:>=2024-01-01T00:00:00Z modified:<2024-01-02T00:00:00Z");

        assertThat(filter.test(original)).isTrue();
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        assertThat(filter.test(original)).isTrue();
        assertThat(filter.test(new StorageSearchSchema.Candidate(file, "report.txt", false))).isFalse();
    }

    @Test
    void sizeAndModifiedShareOneLazySnapshotButTheNextCandidateReadsAgain() throws Exception {
        Path file = root.resolve("report.txt");
        Files.writeString(file, "hello");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));
        var candidate = new StorageSearchSchema.Candidate(file, "report.txt", false);
        assertThat(StorageSearchSchema.compile("size:5").test(candidate)).isTrue();
        Files.delete(file);
        assertThat(StorageSearchSchema.compile("size:>=5 modified:2024-01-01T00:00:00Z").test(candidate)).isTrue();
        assertThatThrownBy(() -> StorageSearchSchema.compile("size:5").test(
                new StorageSearchSchema.Candidate(file, "report.txt", false))).isInstanceOf(StorageAccessException.class);
    }

    @Test
    void sizeSkipsDirectoriesAndBranchesExcludedWithoutMetadataReads() {
        var directory = new StorageSearchSchema.Candidate(root.resolve("missing-folder.pdf"), "missing-folder.pdf", true);
        var file = new StorageSearchSchema.Candidate(root.resolve("missing-report.txt"), "missing-report.txt", false);
        assertThat(StorageSearchSchema.compile("size:0").test(directory)).isFalse();
        assertThat(StorageSearchSchema.compile("size:>=0").test(directory)).isFalse();
        assertThat(StorageSearchSchema.compile("name:other size:1").test(file)).isFalse();
        assertThat(StorageSearchSchema.compile("extension:txt || size:1").test(file)).isTrue();
        assertThatThrownBy(() -> StorageSearchSchema.compile("size:1").test(file)).isInstanceOf(StorageAccessException.class);
    }

    @Test
    void extensionUsesTheLastSuffixAndDoesNotReadMetadataOrMatchDirectoryAndDotfiles() {
        for (String name : new String[] {"a.pdf", "a.PDF", "archive.tar.pdf", ".hidden.pdf"}) {
            assertThat(StorageSearchSchema.compile("extension:PDF").test(
                    new StorageSearchSchema.Candidate(root.resolve(name), name, false))).isTrue();
        }
        for (String name : new String[] {"pdf", "file.pdfx", "file.pd", ".pdf", "file."}) {
            assertThat(StorageSearchSchema.compile("extension:pdf").test(
                    new StorageSearchSchema.Candidate(root.resolve(name), name, false))).isFalse();
        }
        assertThat(StorageSearchSchema.compile("extension:pdf").test(
                new StorageSearchSchema.Candidate(root.resolve("folder.pdf"), "folder.pdf", true))).isFalse();
        assertThat(StorageSearchSchema.compile("extension:gz").test(
                new StorageSearchSchema.Candidate(root.resolve("archive.tar.gz"), "archive.tar.gz", false))).isTrue();
    }
}
