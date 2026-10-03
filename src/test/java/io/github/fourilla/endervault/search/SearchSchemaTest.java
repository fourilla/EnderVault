package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchSchemaTest {

    private record Entry(String name, String path, String type, Instant created) {}

    private final Entry report = new Entry("Summer holiday report", "photos/2026/report.txt", "file",
            Instant.parse("2026-10-02T15:00:00Z"));
    private final SearchSchema<Entry> schema = schema(ZoneId.of("Asia/Seoul"));

    @Test
    void supportsPlainTermsPhrasesFieldsAndLogicalCombinations() {
        assertThat(schema.compile("SUMMER holiday").test(report)).isTrue();
        assertThat(schema.compile("\"summer holiday\"").test(report)).isTrue();
        assertThat(schema.compile("\"holiday summer\"").test(report)).isFalse();
        assertThat(schema.compile("NAME:REPORT type:FILE").test(report)).isTrue();
        assertThat(schema.compile("name:missing || type:file path:2026").test(report)).isTrue();
        assertThat(schema.compile("(name:missing || type:file) path:2025").test(report)).isFalse();
    }

    @Test
    void defaultTargetsAreSuppliedByTheDomainRatherThanInferredFromFields() {
        assertThat(schema.compile("photos").test(report)).isFalse();
        assertThat(schema.compile("path:PHOTOS/2026").test(report)).isTrue();
        SearchSchema<Entry> titleAndPath = new SearchSchema<>(
                (entry, term) -> contains(entry.name(), term) || contains(entry.path(), term), List.of());
        assertThat(titleAndPath.compile("photos").test(report)).isTrue();
    }

    @Test
    void blankQueryMatchesCandidateAndDomainStillControlsBlankInputBehavior() {
        assertThat(schema.compile(null).test(report)).isTrue();
        assertThat(schema.compile(" \t").test(report)).isTrue();
    }

    @Test
    void enumsUseExactAllowedValuesNotSubstringMatching() {
        assertThat(schema.compile("type:directory").test(report)).isFalse();
        assertThatThrownBy(() -> schema.compile("type:fil")).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void validatesEveryBranchBeforeAnyCandidateIsEvaluated() {
        AtomicInteger evaluations = new AtomicInteger();
        SearchSchema<Entry> tracked = new SearchSchema<>((entry, term) -> {
            evaluations.incrementAndGet();
            return true;
        }, List.of(SearchSchema.enumeration("type", "Type", List.of("file"), Entry::type)));

        assertThatThrownBy(() -> tracked.compile("report || type:unknown"))
                .isInstanceOf(SearchQueryException.class);
        assertThatThrownBy(() -> tracked.compile("report || typo:file"))
                .isInstanceOf(SearchQueryException.class);
        assertThat(evaluations).hasValue(0);
    }

    @Test
    void doesNotReadFieldsWhenCompilingOrPublishingMetadata() {
        AtomicInteger extractions = new AtomicInteger();
        SearchSchema<Entry> tracked = new SearchSchema<>((entry, term) -> false,
                List.of(SearchSchema.text("name", "Name", entry -> {
                    extractions.incrementAndGet();
                    return entry.name();
                })));

        Predicate<Entry> predicate = tracked.compile("name:report");
        assertThat(tracked.fields()).hasSize(1);
        assertThat(extractions).hasValue(0);
        assertThat(predicate.test(report)).isTrue();
        assertThat(extractions).hasValue(1);
    }

    @Test
    void unknownFieldsAndUnsupportedOperatorsAreErrorsNotMatchAllFallbacks() {
        assertThatThrownBy(() -> schema.compile("  missing:value"))
                .isInstanceOfSatisfying(SearchQueryException.class, ex -> assertThat(ex.position()).isEqualTo(2));
        assertThatThrownBy(() -> schema.compile("name:>=a")).isInstanceOf(SearchQueryException.class);
        assertThatThrownBy(() -> schema.compile("path:<a")).isInstanceOf(SearchQueryException.class);
        assertThatThrownBy(() -> schema.compile("type:=file")).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void quotingLetsStringFieldsSearchLiteralComparisonPrefixesAndColons() {
        Entry literal = new Entry(">report:part", "photos/(a)&&b", "file", report.created());
        assertThat(schema.compile("name:\">report:part\"").test(literal)).isTrue();
        assertThat(schema.compile("\"report:part\"").test(literal)).isTrue();
        assertThat(schema.compile("path:\"(a)&&b\"").test(literal)).isTrue();
    }

    @Test
    void literalUrlSearchDoesNotNeedASchemeField() {
        Entry link = new Entry("https://example.test/page:8080", "", "file", report.created());
        assertThat(schema.compile("https://example.test/page:8080").test(link)).isTrue();
    }

    @Test
    void nullFieldValuesDoNotMatchOrTurnIntoStrings() {
        Entry empty = new Entry(null, null, null, null);
        assertThat(schema.compile("name:null || path:null || type:file || created:2026-10-03").test(empty))
                .isFalse();
    }

    @Test
    void supportsUnicodeTextWithoutDiscardingOrNormalizingFileCharacters() {
        Entry unicode = new Entry("\ud734\uac00 \uc0ac\uc9c4 \ud83d\udcf7", "\uc0ac\uc9c4/2026", "file", report.created());
        assertThat(schema.compile("\uc0ac\uc9c4 \ud83d\udcf7").test(unicode)).isTrue();
        assertThat(schema.compile("path:\uc0ac\uc9c4").test(unicode)).isTrue();
        assertThat(schema.compile("name:I").test(new Entry("i", "", "file", report.created()))).isTrue();
    }

    @Test
    void dateOnlyValuesCoverTheWholeDayInTheSchemaTimeZone() {
        Predicate<Entry> sameDay = schema.compile("created:2026-10-03");
        assertThat(sameDay.test(report)).isTrue();
        assertThat(sameDay.test(at("2026-10-02T14:59:59.999999999Z"))).isFalse();
        assertThat(sameDay.test(at("2026-10-03T14:59:59.999999999Z"))).isTrue();
        assertThat(sameDay.test(at("2026-10-03T15:00:00Z"))).isFalse();
    }

    @Test
    void dateOnlyComparisonsRespectDayBoundaries() {
        assertThat(schema.compile("created:>=2026-10-03").test(report)).isTrue();
        assertThat(schema.compile("created:<2026-10-03").test(report)).isFalse();
        assertThat(schema.compile("created:<=2026-10-03").test(at("2026-10-03T14:59:59Z"))).isTrue();
        assertThat(schema.compile("created:>2026-10-03").test(at("2026-10-03T14:59:59Z"))).isFalse();
        assertThat(schema.compile("created:>2026-10-03").test(at("2026-10-03T15:00:00Z"))).isTrue();
    }

    @Test
    void offsetTimestampsAreExactInstantsAndSupportComparisons() {
        assertThat(schema.compile("created:2026-10-03T00:00:00+09:00").test(report)).isTrue();
        assertThat(schema.compile("created:2026-10-02T15:00:00Z").test(report)).isTrue();
        assertThat(schema.compile("created:>2026-10-02T15:00:00Z").test(report)).isFalse();
        assertThat(schema.compile("created:>=2026-10-02T15:00:00Z").test(report)).isTrue();
        assertThat(schema.compile("created:<=2026-10-02T15:00:00Z").test(report)).isTrue();
        assertThat(schema.compile("created:<2026-10-02T15:00:00Z").test(report)).isFalse();
        assertThat(schema.compile("created:2026-10-02T15:00:00.000000001Z").test(report)).isFalse();
    }

    @Test
    void dateRangesIncludeTheEntireLastDateAndTimestampRangesIncludeEndpoints() {
        Predicate<Entry> dates = schema.compile("created:2026-10-01..2026-10-03");
        assertThat(dates.test(at("2026-09-30T15:00:00Z"))).isTrue();
        assertThat(dates.test(at("2026-10-03T14:59:59Z"))).isTrue();
        assertThat(dates.test(at("2026-10-03T15:00:00Z"))).isFalse();
        assertThat(schema.compile("created:2026-10-03..2026-10-03").test(report)).isTrue();
        assertThat(schema.compile("created:2026-10-02T15:00:00Z..2026-10-02T15:00:00Z").test(report)).isTrue();
        assertThat(schema.compile("created:2026-10-02T15:00:00Z..2026-10-03").test(report)).isTrue();
    }

    @Test
    void calculatesDstDayEndFromTheNextLocalMidnightNotTwentyFourHours() {
        SearchSchema<Entry> newYork = schema(ZoneId.of("America/New_York"));
        Predicate<Entry> springDay = newYork.compile("created:2026-03-08");
        assertThat(springDay.test(at("2026-03-08T05:00:00Z"))).isTrue();
        assertThat(springDay.test(at("2026-03-09T03:59:59Z"))).isTrue();
        assertThat(springDay.test(at("2026-03-09T04:00:00Z"))).isFalse();
        Predicate<Entry> fallDay = newYork.compile("created:2026-11-01");
        assertThat(fallDay.test(at("2026-11-02T04:59:59Z"))).isTrue();
        assertThat(fallDay.test(at("2026-11-02T05:00:00Z"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "created:2026-02-30", "created:2026-10-3", "created:yesterday",
            "created:2026-10-03T09:00:00", "created:>=", "created:=2026-10-03",
            "created:2026-10-04..2026-10-03", "created:..2026-10-03", "created:2026-10-03..",
            "created:>=2026-10-01..2026-10-03", "created:2026-10-01..2026-10-02..2026-10-03",
            "created:2026-10-03T16:00:00Z..2026-10-03", "created:+999999999-12-31"
    })
    void rejectsInvalidDatesRangesAndAmbiguousOffsetlessTimes(String query) {
        assertThatThrownBy(() -> schema.compile(query)).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void schemaMetadataIsImmutableAndPreservesDeclarationOrder() {
        List<String> allowed = new ArrayList<>(List.of("file", "directory"));
        List<SearchSchema.Field<Entry>> fields = new ArrayList<>(List.of(
                SearchSchema.text("NAME", "Name", Entry::name),
                SearchSchema.enumeration("type", "Type", allowed, Entry::type),
                SearchSchema.dateTime("created", "Created", ZoneId.of("Asia/Seoul"), Entry::created)));
        SearchSchema<Entry> snapshot = new SearchSchema<>((entry, term) -> false, fields);
        allowed.clear();
        fields.clear();

        assertThat(snapshot.fields()).extracting(SearchSchema.FieldInfo::key)
                .containsExactly("name", "type", "created");
        assertThat(snapshot.fields().get(0).operators()).containsExactly(SearchSchema.Operator.CONTAINS);
        assertThat(snapshot.fields().get(1).values()).containsExactly("file", "directory");
        assertThat(snapshot.fields().get(2).timeZone()).isEqualTo("Asia/Seoul");
        assertThat(snapshot.compile("type:file").test(report)).isTrue();
        assertThatThrownBy(() -> snapshot.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.fields().get(1).values().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void schemaRejectsDuplicateKeysAmbiguousEnumValuesAndInvalidFieldNames() {
        assertThatThrownBy(() -> new SearchSchema<Entry>((entry, term) -> false, List.of(
                SearchSchema.text("name", "Name", Entry::name),
                SearchSchema.text("NAME", "Duplicate", Entry::name))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> SearchSchema.enumeration("type", "Type", List.of("FILE", "file"), Entry::type))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchSchema.enumeration("type", "Type", List.of(), Entry::type))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchSchema.text("invalid key", "Name", Entry::name))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomIncompleteInputsEitherCompileOrFailWithAQueryError() {
        Random random = new Random(41528);
        String characters = "ab ()&|:\"\\\t123<>.\uc0ac\uc9c4";
        for (int attempt = 0; attempt < 2000; attempt++) {
            StringBuilder query = new StringBuilder();
            int length = random.nextInt(80);
            for (int index = 0; index < length; index++) {
                query.append(characters.charAt(random.nextInt(characters.length())));
            }
            try {
                schema.compile(query.toString()).test(report);
            } catch (SearchQueryException ex) {
                assertThat(ex.position()).isBetween(0, query.length());
            }
        }
    }

    private SearchSchema<Entry> schema(ZoneId zone) {
        return new SearchSchema<>((entry, term) -> contains(entry.name(), term), List.of(
                SearchSchema.text("name", "Name", Entry::name),
                SearchSchema.path("path", "Path", Entry::path),
                SearchSchema.enumeration("type", "Type", List.of("file", "directory"), Entry::type),
                SearchSchema.dateTime("created", "Created", zone, Entry::created)));
    }

    private boolean contains(String actual, String term) {
        return actual != null && actual.toLowerCase(java.util.Locale.ROOT).contains(term);
    }

    private Entry at(String instant) {
        return new Entry(report.name(), report.path(), report.type(), Instant.parse(instant));
    }
}
