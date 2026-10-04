package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchNumberSchemaTest {
    private record Entry(String name, Long size, Long count) {}
    private final SearchSchema<Entry> schema = new SearchSchema<>((item, term) -> item.name().contains(term), List.of(
            SearchSchema.byteSize("size", "Size", Entry::size),
            SearchSchema.number("count", "Count", Entry::count),
            SearchSchema.exactText("extension", "Extension", Entry::name)));

    @ParameterizedTest
    @CsvSource({"1,1", "1B,1", "1KB,1000", "1MB,1000000", "1GB,1000000000", "1TB,1000000000000",
            "1KiB,1024", "1MiB,1048576", "1GiB,1073741824", "1TiB,1099511627776",
            "1mIb,1048576", "1.5MiB,1572864", "0.001KB,1", "0.0009765625KiB,1", "0,0", "0.0B,0",
            "9223372036854775807B,9223372036854775807"})
    void byteUnitsConvertExactlyWithoutFloatingPointOrRounding(String value, long expected) {
        assertThat(schema.compile("size:" + value).test(new Entry("file", expected, null))).isTrue();
        assertThat(schema.compile("size:" + value).test(new Entry("file", expected - 1, null))).isFalse();
    }

    @Test
    void byteComparisonsAndInclusiveRangesUseLongBoundsWithoutOverflow() {
        var item = new Entry("file", 1024L, null);
        for (String value : List.of("1KiB", "=1024", ">=1024", "<=1024", ">1023", "<1025", "1KB..1KiB",
                "1024..1024", "\">=1KiB\"")) {
            assertThat(schema.compile("size:" + value).test(item)).as(value).isTrue();
        }
        for (String value : List.of(">1024", "<1024", "1025..2048", "0..1023", "1MB")) {
            assertThat(schema.compile("size:" + value).test(item)).as(value).isFalse();
        }
        assertThat(schema.compile("size:>9223372036854775807").test(new Entry("file", Long.MAX_VALUE, null))).isFalse();
        assertThat(schema.compile("size:0..9223372036854775807").test(new Entry("file", Long.MAX_VALUE, null))).isTrue();
    }

    @Test
    void integersSupportSignedBoundsAndDoNotAcceptByteUnitsOrDecimalCounts() {
        var negative = new Entry("file", null, Long.MIN_VALUE);
        assertThat(schema.compile("count:-9223372036854775808").test(negative)).isTrue();
        assertThat(schema.compile("count:<0 count:>=-9223372036854775808").test(negative)).isTrue();
        assertThat(schema.compile("count:-9223372036854775808..9223372036854775807").test(negative)).isTrue();
        assertThat(schema.compile("count:=0").test(new Entry("file", null, 0L))).isTrue();
        assertThat(schema.compile("count:>=1 count:<=3").test(new Entry("file", null, 2L))).isTrue();
        assertThat(schema.compile("count:<-9223372036854775808").test(negative)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"size:-1", "size:+1", "size:1XB", "size:1K", "size:1PB", "size:1e3", "size:NaN",
            "size:Infinity", "size:0.1B", "size:1.1KiB", "size:9223372036854775808", "size:9223372036854775807KB",
            "size:.5MB", "size:1.MB", "size:=", "size:>=", "size:==1", "size:!=1", "size:..1", "size:1..",
            "size:2..1", "size:1MiB..1MB", "size:1..2..3", "size:>=1..2", "size:\"1 MB\"", "size:\" 1\"",
            "count:1KB", "count:1.0", "count:9223372036854775808", "count:-9223372036854775809",
            "count:1..-1", "count:+1", "count:1e3"})
    void invalidConditionsFailAtCompileTimeEvenInAnUnusedOrBranch(String query) {
        assertThatThrownBy(() -> schema.compile("file || " + query))
                .isInstanceOf(SearchQueryException.class);
    }

    @Test
    void metadataIsTypedImmutableAndValidationDoesNotReadCandidates() {
        AtomicInteger reads = new AtomicInteger();
        SearchSchema<Entry> tracked = new SearchSchema<>((item, term) -> false, List.of(
                SearchSchema.byteSize("size", "Size", item -> { reads.incrementAndGet(); return item.size(); })));
        var field = tracked.fields().getFirst();
        assertThat(field.type()).isEqualTo(SearchSchema.ValueType.NUMBER);
        assertThat(field.units()).containsExactly("B", "KB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB");
        assertThat(field.operators()).containsExactly(SearchSchema.Operator.EQUALS, SearchSchema.Operator.LESS_THAN,
                SearchSchema.Operator.LESS_THAN_OR_EQUAL, SearchSchema.Operator.GREATER_THAN,
                SearchSchema.Operator.GREATER_THAN_OR_EQUAL, SearchSchema.Operator.RANGE);
        assertThatThrownBy(() -> field.units().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(schema.fields().get(1).units()).isEmpty();
        var predicate = tracked.compile("size:1KiB..2KiB");
        assertThat(reads).hasValue(0);
        assertThatThrownBy(() -> tracked.compile("  size:invalid"))
                .isInstanceOfSatisfying(SearchQueryException.class, error -> assertThat(error.position()).isEqualTo(2));
        assertThat(reads).hasValue(0);
        assertThat(predicate.test(new Entry("file", 1024L, null))).isTrue();
        assertThat(reads).hasValue(1);
    }

    @Test
    void unknownSizesNeverMatchZeroOrInequalitiesAndCheapBranchesStillShortCircuit() {
        Entry unknown = new Entry("file", null, null);
        for (String query : List.of("size:0", "size:>=0", "size:<1KB", "size:0..1KB", "count:0")) {
            assertThat(schema.compile(query).test(unknown)).as(query).isFalse();
        }
        assertThat(schema.compile("size:<1KB").test(new Entry("file", -1L, null))).isFalse();
        var tracked = new SearchSchema<Entry>((item, term) -> item.name().equals(term), List.of(
                SearchSchema.byteSize("size", "Size", item -> { throw new AssertionError("Unexpected read"); })));
        assertThat(tracked.compile("file || size:0").test(unknown)).isTrue();
        assertThat(tracked.compile("missing size:0").test(unknown)).isFalse();
    }

    @Test
    void exactTextSupportsOpenEndedSuffixesWithoutSubstringMatchingOrEnumRegistry() {
        Entry pdf = new Entry("pdf", null, null);
        assertThat(schema.compile("extension:PDF").test(pdf)).isTrue();
        assertThat(schema.compile("extension:pd").test(pdf)).isFalse();
        assertThat(schema.compile("extension:pdfx").test(pdf)).isFalse();
        assertThat(schema.compile("extension:custom").test(new Entry("custom", null, null))).isTrue();
        assertThat(schema.compile("extension:pdf").test(new Entry(null, null, null))).isFalse();
        assertThatThrownBy(() -> schema.compile("extension:>=pdf")).isInstanceOf(SearchQueryException.class);
        assertThat(schema.fields().get(2).operators()).containsExactly(SearchSchema.Operator.EQUALS);
    }
}
