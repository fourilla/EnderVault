package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchQueryParserTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void blankQueryDoesNotCreateAnEmptyCondition(String query) {
        assertThat(SearchQueryParser.parse(query)).isInstanceOf(SearchQueryParser.All.class);
    }

    @Test
    void whitespaceAndExplicitAndBindMoreTightlyThanOr() {
        assertThat(SearchQueryParser.parse("alpha beta||gamma&&delta")).isEqualTo(
                new SearchQueryParser.Or(List.of(
                        new SearchQueryParser.And(List.of(term("alpha", 0), term("beta", 6))),
                        new SearchQueryParser.And(List.of(term("gamma", 12), term("delta", 19)))
                )));
    }

    @Test
    void groupsOverridePrecedence() {
        assertThat(SearchQueryParser.parse("(alpha||beta) gamma")).isEqualTo(
                new SearchQueryParser.And(List.of(
                        new SearchQueryParser.Or(List.of(term("alpha", 1), term("beta", 8))),
                        term("gamma", 14)
                )));
    }

    @Test
    void preservesQuotedPhrasesAndLiteralSyntax() {
        assertThat(SearchQueryParser.parse("\"name:a && (b)||c\"")).isEqualTo(
                new SearchQueryParser.Term(null, "name:a && (b)||c", true, 0));
        assertThat(SearchQueryParser.parse("NAME:\"summer holiday\"")).isEqualTo(
                new SearchQueryParser.Term("name", "summer holiday", true, 0));
    }

    @Test
    void supportsWhitespaceAfterColonButRequiresAValue() {
        assertThat(SearchQueryParser.parse("name: \"summer holiday\"")).isEqualTo(
                new SearchQueryParser.Term("name", "summer holiday", true, 0));
    }

    @Test
    void onlyUnescapesQuotesAndBackslashesInsideQuotes() {
        assertThat(SearchQueryParser.parse("\"a\\\"b\\\\c\"")).isEqualTo(
                new SearchQueryParser.Term(null, "a\"b\\c", true, 0));
        assertThat(SearchQueryParser.parse("\"C:\\Users\\PC\"")).isEqualTo(
                new SearchQueryParser.Term(null, "C:\\Users\\PC", true, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.test/a:80", "http://example.test", "C:\\Users\\PC", "C:/Users/PC"})
    void treatsSchemeUrlsAndDrivePathsAsPlainTerms(String value) {
        assertThat(SearchQueryParser.parse(value)).isEqualTo(term(value, 0));
    }

    @Test
    void preservesDateTimeColonsInsideFieldValue() {
        assertThat(SearchQueryParser.parse("created:>=2026-10-03T09:00:00+09:00")).isEqualTo(
                new SearchQueryParser.Term("created", ">=2026-10-03T09:00:00+09:00", false, 0));
    }

    @Test
    void singleAmpersandAndPipeAreLiteralCharacters() {
        assertThat(SearchQueryParser.parse("a&b|c")).isEqualTo(term("a&b|c", 0));
    }

    @Test
    void flattensConsecutiveOperationsRatherThanCreatingALeftDeepTree() {
        SearchQueryParser.And expression = (SearchQueryParser.And) SearchQueryParser.parse("a b c d");
        assertThat(expression.terms()).hasSize(4).allMatch(SearchQueryParser.Term.class::isInstance);
        assertThatThrownBy(() -> expression.terms().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "()", "( )", "(a", "a)", ")a(", "a &&", "|| a", "a || || b", "a && && b",
            "\"unfinished", "name:", "name: || other", "name:()", "name:\"\"", "\"\"", "\" \"",
            "a(b)", "(a)(b)", "\"a\"\"b\"", "a\"b\"", "name:\"a\"suffix", "name:\"a\":suffix"
    })
    void rejectsMalformedExpressionsInsteadOfDroppingConditions(String query) {
        assertThatThrownBy(() -> SearchQueryParser.parse(query)).isInstanceOf(SearchQueryException.class);
    }

    @Test
    void reportsPositionsInTheOriginalUntrimmedInput() {
        assertThatThrownBy(() -> SearchQueryParser.parse("  name:"))
                .isInstanceOfSatisfying(SearchQueryException.class, ex -> assertThat(ex.position()).isEqualTo(7));
        assertThatThrownBy(() -> SearchQueryParser.parse("  \"unfinished"))
                .isInstanceOfSatisfying(SearchQueryException.class, ex -> assertThat(ex.position()).isEqualTo(2));
    }

    @Test
    void enforcesLengthLimitWithoutTruncatingTheInput() {
        assertThat(SearchQueryParser.parse("a".repeat(SearchQueryParser.MAX_LENGTH)))
                .isInstanceOf(SearchQueryParser.Term.class);
        assertThatThrownBy(() -> SearchQueryParser.parse("a".repeat(SearchQueryParser.MAX_LENGTH + 1)))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("too long");
    }

    @Test
    void enforcesTermLimitForImplicitAnd() {
        assertThat(SearchQueryParser.parse("a ".repeat(SearchQueryParser.MAX_TERMS)))
                .isInstanceOf(SearchQueryParser.And.class);
        assertThatThrownBy(() -> SearchQueryParser.parse("a ".repeat(SearchQueryParser.MAX_TERMS + 1)))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("conditions");
    }

    @Test
    void enforcesTokenLimitIncludingFieldValueTokens() {
        assertThat(SearchQueryParser.parse("name:\"a\" ".repeat(SearchQueryParser.MAX_TOKENS / 2)))
                .isInstanceOf(SearchQueryParser.And.class);
        assertThatThrownBy(() -> SearchQueryParser.parse("name:\"a\" ".repeat(SearchQueryParser.MAX_TOKENS / 2 + 1)))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("tokens");
    }

    @Test
    void enforcesGroupDepthBeforeRecursingFurther() {
        assertThat(SearchQueryParser.parse("(".repeat(SearchQueryParser.MAX_DEPTH) + "a"
                + ")".repeat(SearchQueryParser.MAX_DEPTH))).isEqualTo(term("a", SearchQueryParser.MAX_DEPTH));
        assertThatThrownBy(() -> SearchQueryParser.parse("(".repeat(SearchQueryParser.MAX_DEPTH + 1) + "a"
                + ")".repeat(SearchQueryParser.MAX_DEPTH + 1)))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("nested");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "destination", "createdAt", "source-type", "field_2"})
    void permitsStableAsciiFieldKeys(String key) {
        assertThat(SearchQueryParser.isFieldName(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2name", "field.name", "field name", "\ud544\ub4dc", "a:b"})
    void doesNotTreatArbitraryTextAsAFieldKey(String key) {
        assertThat(SearchQueryParser.isFieldName(key)).isFalse();
    }

    private SearchQueryParser.Term term(String value, int position) {
        return new SearchQueryParser.Term(null, value, false, position);
    }
}
