package io.github.fourilla.endervault.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.search.SearchSchema;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrashSearchSchemaTest {
    private static final Instant DELETED = Instant.parse("2026-10-04T10:30:00Z");

    private TrashRecord item(String name, boolean directory, Instant expires) {
        return new TrashRecord("internal-id", "photos/" + name, "photos", name, "internal-trash-name",
                directory, 10, "display size", "display type", DELETED, expires);
    }

    @Test
    void defaultsUseOriginalNamesAndPathsRatherThanInternalIdsOrDisplayLabels() {
        var record = item("Summer holiday.PDF", false, null);
        assertThat(TrashSearchSchema.compile("PHOTOS \"summer holiday\"").test(record)).isTrue();
        assertThat(TrashSearchSchema.compile("name:PHOTOS").test(record)).isFalse();
        assertThat(TrashSearchSchema.compile("name:holiday path:photos type:FILE extension:pdf").test(record)).isTrue();
        assertThat(TrashSearchSchema.compile("name:absent || (path:photos type:file)").test(record)).isTrue();
        for (String query : List.of("internal-id", "internal-trash-name", "display type", "display size")) {
            assertThat(TrashSearchSchema.compile(query).test(record)).as(query).isFalse();
        }
        for (String query : new String[] {null, "", "  "}) {
            assertThat(TrashSearchSchema.compile(query).test(record)).isTrue();
        }
    }

    @Test
    void extensionIsExactAndExcludesDirectoriesAndDotOnlyNames() {
        assertThat(TrashSearchSchema.compile("extension:pdf").test(item("report.PDF", false, null))).isTrue();
        assertThat(TrashSearchSchema.compile("extension:pd").test(item("report.pdf", false, null))).isFalse();
        assertThat(TrashSearchSchema.compile("extension:gz").test(item("backup.tar.gz", false, null))).isTrue();
        for (String name : List.of(".pdf", "report.", "report")) {
            assertThat(TrashSearchSchema.compile("extension:pdf").test(item(name, false, null))).isFalse();
        }
        assertThat(TrashSearchSchema.compile("extension:pdf").test(item("folder.pdf", true, null))).isFalse();
        assertThat(TrashSearchSchema.compile("type:DIRECTORY").test(item("folder.pdf", true, null))).isTrue();
    }

    @Test
    void datesCompareOriginalInstantsIncludingBoundsAndExcludeNoExpiry() {
        var record = item("report.pdf", false, DELETED.plusSeconds(3600));
        assertThat(TrashSearchSchema.compile("deleted:2026-10-04T10:30:00Z expires:>2026-10-04T10:30:00Z")
                .test(record)).isTrue();
        assertThat(TrashSearchSchema.compile("deleted:2026-10-04T10:00:00Z..2026-10-04T10:30:00Z")
                .test(record)).isTrue();
        assertThat(TrashSearchSchema.compile("deleted:>2026-10-04T10:30:00Z").test(record)).isFalse();
        assertThat(TrashSearchSchema.compile("expires:>=2026-10-04T10:30:00Z")
                .test(item("report.pdf", false, null))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"photos || typo:value", "photos || type:unknown", "deleted:2026-02-30",
            "expires:never", "deleted:2026-10-05..2026-10-04", "extension:>pdf", "name:", "size:0", "status:expired"})
    void validatesTheWholeQueryBeforeCandidateEvaluation(String query) {
        assertThatThrownBy(() -> TrashSearchSchema.compile(query)).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void metadataComesFromTheRegisteredSchemaAndIsImmutable() {
        assertThat(TrashSearchSchema.defaultFields()).containsExactly("name", "path");
        assertThat(TrashSearchSchema.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "path", "type", "extension", "deleted", "expires");
        assertThat(TrashSearchSchema.fields().get(2).values()).containsExactly("file", "directory");
        assertThat(TrashSearchSchema.fields().get(3).operators()).containsExactly(SearchSchema.Operator.EQUALS);
        assertThatThrownBy(() -> TrashSearchSchema.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
