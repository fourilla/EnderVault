package io.github.fourilla.endervault.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.pending.PendingDecisionSearchSchema.Candidate;
import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.search.SearchSchema;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingDecisionSearchSchemaTest {

    private final Candidate file = new Candidate("summer holiday.jpg", "/photos/2026", "FILE_REQUEST",
            false, Instant.parse("2026-10-03T09:00:00Z"), "Alice", () -> PendingDecisionStatus.AWAITING_DECISION);

    @Test
    void defaultMatchesNameOrDestinationButNotUploaderOrSource() {
        assertThat(matches("SUMMER photos", file)).isTrue();
        assertThat(matches("\"summer holiday\"", file)).isTrue();
        assertThat(matches("\"holiday summer\"", file)).isFalse();
        assertThat(matches("name:photos", file)).isFalse();
        assertThat(matches("Alice", file)).isFalse();
        assertThat(matches("file_request", file)).isFalse();
    }

    @Test
    void typedConditionsAndGroupsUseTheCommonEngine() {
        assertThat(matches("(source:admin_upload || source:FILE_REQUEST) type:file destination:2026 submitter:ali", file)).isTrue();
        assertThat(matches("type:directory || source:directory_copy", file)).isFalse();
        assertThat(matches("created:>=2026-10-03T09:00:00+00:00 created:<=2026-10-03T09:00:00Z", file)).isTrue();
        assertThat(matches("created:>2026-10-03T09:00:00Z", file)).isFalse();
        assertThat(matches("created:2026-10-01T00:00:00Z..2026-10-04T00:00:00Z", file)).isTrue();
        assertThat(matches("status:AWAITING_DECISION", file)).isTrue();
        assertThat(matches("status:paused", file)).isFalse();
    }

    @Test
    void absentSubmitterAndRootDestinationKeepTheirDomainMeanings() {
        var decision = new PendingFileDecision("id", PendingFileDecisionSource.ADMIN_UPLOAD,
                "secret-staging", null, "report.txt", 1, Instant.EPOCH, null, null, null);
        var candidate = Candidate.from(decision, () -> PendingDecisionStatus.AWAITING_DECISION);
        assertThat(candidate.destination()).isEqualTo("/");
        assertThat(matches("destination:/ source:admin_upload type:file", candidate)).isTrue();
        assertThat(matches("submitter:null", candidate)).isFalse();
        assertThat(matches("name:secret-staging", candidate)).isFalse();
        assertThat(matches("destination:/", file)).isTrue(); // CONTAINS is not an exact root selector.
    }

    @Test
    void sourceRegistryIncludesEveryExistingSourceAndStandaloneTransfers() {
        var sourceField = PendingDecisionSearchSchema.fields().stream().filter(field -> field.key().equals("source"))
                .findFirst().orElseThrow();
        for (var source : PendingFileDecisionSource.values()) {
            assertThat(sourceField.values()).contains(source.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(matches("source:" + source.name(), new Candidate("file", "/", source.name(), false, Instant.EPOCH, null,
                    () -> PendingDecisionStatus.AWAITING_DECISION)))
                    .isTrue();
        }
        assertThat(sourceField.values()).contains("directory_copy", "directory_move");
        assertThat(PendingDecisionSearchSchema.defaultFields()).containsExactly("name", "destination");
        assertThat(PendingDecisionSearchSchema.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "destination", "source", "type", "created", "submitter", "status");
        assertThatThrownBy(() -> sourceField.values().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void statusIsEvaluatedOnlyWhenReachedAndDoesNotMatchDisplayTextByDefault() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var candidate = new Candidate("photo", "/target", "DIRECTORY_UPLOAD", true, Instant.EPOCH, null,
                () -> { reads.incrementAndGet(); return PendingDecisionStatus.PAUSED; });
        assertThat(matches("name:missing status:paused", candidate)).isFalse();
        assertThat(matches("paused", candidate)).isFalse();
        assertThat(reads).hasValue(0);
        assertThat(matches("name:photo status:paused", candidate)).isTrue();
        assertThat(reads).hasValue(1);
        var statusField = PendingDecisionSearchSchema.fields().stream().filter(field -> field.key().equals("status"))
                .findFirst().orElseThrow();
        assertThat(statusField.values()).contains("awaiting_decision", "preparing_review", "needs_review", "recovery_required")
                .doesNotContain("complete", "abandoned");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source:upload", "type:zip", "created:yesterday", "created:2026-10-03T09:00:00",
            "name:summer || status:merging", "name:summer || source:invalid", "size:>=1MB"})
    void invalidTermsAreRejectedEvenWhenAnotherOrBranchWouldMatch(String query) {
        assertThatThrownBy(() -> PendingDecisionSearchSchema.compile(query)).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void emptyQueryPreservesAllCandidatesAndInputPositionsAreNotTrimmed() {
        assertThat(matches(null, file)).isTrue();
        assertThat(matches("  ", file)).isTrue();
        assertThatThrownBy(() -> PendingDecisionSearchSchema.compile("  invalid:value"))
                .isInstanceOfSatisfying(SearchQueryException.class, error -> assertThat(error.position()).isEqualTo(2));
    }

    private boolean matches(String query, Candidate item) {
        return PendingDecisionSearchSchema.compile(query).test(item);
    }
}
