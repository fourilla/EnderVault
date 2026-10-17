package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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
    void negationBindsMoreTightlyThanAndAndOr() {
        assertThat(SearchQueryParser.parse("!alpha beta || !(gamma && !!delta)")).isEqualTo(
                new SearchQueryParser.Or(List.of(
                        new SearchQueryParser.And(List.of(
                                new SearchQueryParser.Not(term("alpha", 1)), term("beta", 7))),
                        new SearchQueryParser.Not(new SearchQueryParser.And(List.of(
                                term("gamma", 17),
                                new SearchQueryParser.Not(new SearchQueryParser.Not(term("delta", 28))))))
                )));
    }

    @Test
    void negatesFieldConditionsWithoutRewritingTheirValues() {
        assertThat(SearchQueryParser.parse("!name:report !size:=0")).isEqualTo(
                new SearchQueryParser.And(List.of(
                        new SearchQueryParser.Not(new SearchQueryParser.Term("name", "report", false, 1)),
                        new SearchQueryParser.Not(new SearchQueryParser.Term("size", "=0", false, 14))
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
    void preservesQuotedAndUnquotedFragmentsInOneValue() {
        SearchQueryParser.Term term = (SearchQueryParser.Term) SearchQueryParser.parse(
                "name:\"annual report\"*.pdf");
        assertThat(term.field()).isEqualTo("name");
        assertThat(term.value()).isEqualTo("annual report*.pdf");
        assertThat(term.quoted()).isFalse();
        assertThat(term.parts()).containsExactly(
                new SearchQueryParser.ValuePart("annual report", true),
                new SearchQueryParser.ValuePart("*.pdf", false));
        assertThat(term.hasWildcards()).isTrue();
        assertThat(term.hasUnquotedPrefix("=")).isFalse();

        SearchQueryParser.Term literalWildcard = (SearchQueryParser.Term) SearchQueryParser.parse("name:*\"a*b\"*");
        assertThat(literalWildcard.parts()).containsExactly(
                new SearchQueryParser.ValuePart("*", false),
                new SearchQueryParser.ValuePart("a*b", true),
                new SearchQueryParser.ValuePart("*", false));
        assertThat(literalWildcard.value()).isEqualTo("*a*b*");
    }

    @Test
    void preservesTheUnquotedEqualityPrefixBeforeAQuotedValue() {
        SearchQueryParser.Term term = (SearchQueryParser.Term) SearchQueryParser.parse(
                "name:=\"annual report.pdf\"");
        assertThat(term.value()).isEqualTo("=annual report.pdf");
        assertThat(term.parts()).containsExactly(
                new SearchQueryParser.ValuePart("=", false),
                new SearchQueryParser.ValuePart("annual report.pdf", true));
        assertThat(term.hasUnquotedPrefix("=")).isTrue();
        assertThat(term.hasWildcards()).isFalse();
        assertThat(SearchQueryParser.parse("name: =\"annual report.pdf\"")).isEqualTo(term);

        SearchQueryParser.Term literalPrefix = (SearchQueryParser.Term) SearchQueryParser.parse("name:\"=a*?\"suffix");
        assertThat(literalPrefix.hasUnquotedPrefix("=")).isFalse();
        assertThat(literalPrefix.hasWildcards()).isFalse();
    }

    @Test
    void emptyQuotedFragmentsDoNotHideUnquotedOperators() {
        SearchQueryParser.Term exact = (SearchQueryParser.Term) SearchQueryParser.parse("name:\"\"=report");
        assertThat(exact.hasUnquotedPrefix("=")).isTrue();
        SearchQueryParser.Term literal = (SearchQueryParser.Term) SearchQueryParser.parse("name:\"\"\"=\"report");
        assertThat(literal.hasUnquotedPrefix("=")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"!report", "!!report", "!"})
    void whitespaceAfterColonDoesNotTurnAValueIntoNegation(String value) {
        SearchQueryParser.Term term = (SearchQueryParser.Term) SearchQueryParser.parse("name: " + value);
        assertThat(term.field()).isEqualTo("name");
        assertThat(term.value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\"\"b\"", "a\"b\"", "name:\"a\"suffix", "name:\"a\":suffix"})
    void deliberatelyAllowsAdjacentLiteralValueFragments(String query) {
        assertThat(SearchQueryParser.parse(query)).isInstanceOf(SearchQueryParser.Term.class);
    }

    @Test
    void copiesValuePartsAndKeepsThemImmutable() {
        List<SearchQueryParser.ValuePart> mutableParts = new ArrayList<>(List.of(
                new SearchQueryParser.ValuePart("a", true), new SearchQueryParser.ValuePart("*", false)));
        SearchQueryParser.Term term = new SearchQueryParser.Term("name", "a*", false, 0, mutableParts);
        mutableParts.clear();
        assertThat(term.parts()).hasSize(2);
        assertThatThrownBy(() -> term.parts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SearchQueryParser.Term("name", "a*", true, 0).hasWildcards()).isFalse();
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

    @ParameterizedTest
    @ValueSource(strings = {"a!b", "name:a!b", "name:!report", "https://example.test/a!b", "C:/Users/!folder"})
    void preservesExclamationMarksInsideWordsAndFieldValues(String query) {
        assertThat(SearchQueryParser.parse(query)).isInstanceOf(SearchQueryParser.Term.class);
    }

    @Test
    void quotedExclamationMarksAndAdjacentValueFragmentsStayLiteral() {
        assertThat(SearchQueryParser.parse("\"!report\"")).isEqualTo(
                new SearchQueryParser.Term(null, "!report", true, 0));
        SearchQueryParser.Term term = (SearchQueryParser.Term) SearchQueryParser.parse("name:\"a\"!b");
        assertThat(term.value()).isEqualTo("a!b");
        assertThat(term.parts()).containsExactly(
                new SearchQueryParser.ValuePart("a", true), new SearchQueryParser.ValuePart("!b", false));
    }

    @Test
    void doesNotInterpretQuotedFieldKeysAsAFieldDeclaration() {
        SearchQueryParser.Term term = (SearchQueryParser.Term) SearchQueryParser.parse("\"name\":report");
        assertThat(term.field()).isNull();
        assertThat(term.value()).isEqualTo("name:report");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!=0", "! !=0", "size:!=0", "size: !=0", "name:\"\"!=0", "name:!\"\"=0"})
    void rejectsTheExcludedNotEqualsAlias(String query) {
        assertThatThrownBy(() -> SearchQueryParser.parse(query))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("!= operator");
    }

    @Test
    void preservesLiteralNotEqualsCharacters() {
        assertThat(SearchQueryParser.parse("\"!=0\"")).isEqualTo(
                new SearchQueryParser.Term(null, "!=0", true, 0));
        assertThat(SearchQueryParser.parse("name:\"!=0\"")).isEqualTo(
                new SearchQueryParser.Term("name", "!=0", true, 0));
        assertThat(SearchQueryParser.parse("a!=b")).isEqualTo(term("a!=b", 0));
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
            "a(b)", "(a)(b)", "(a)!b", "!a(b)", "!", "!!", "!()", "!( )", "!||a", "a && !",
            "!name:", "!\"\""
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
        assertThatThrownBy(() -> SearchQueryParser.parse("  !name:"))
                .isInstanceOfSatisfying(SearchQueryException.class, ex -> assertThat(ex.position()).isEqualTo(8));
        assertThatThrownBy(() -> SearchQueryParser.parse("  !=0"))
                .isInstanceOfSatisfying(SearchQueryException.class, ex -> assertThat(ex.position()).isEqualTo(2));
        SearchQueryParser.Not not = (SearchQueryParser.Not) SearchQueryParser.parse("  ! name:report");
        assertThat(((SearchQueryParser.Term) not.expression()).position()).isEqualTo(4);
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

    @Test
    void boundsUnaryNegationsAndGroupsWithOneDepthBudget() {
        assertThat(SearchQueryParser.parse("!".repeat(SearchQueryParser.MAX_DEPTH) + "a"))
                .isInstanceOf(SearchQueryParser.Not.class);
        assertThatThrownBy(() -> SearchQueryParser.parse("!".repeat(SearchQueryParser.MAX_DEPTH + 1) + "a"))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("nested");
        assertThat(SearchQueryParser.parse("!(".repeat(SearchQueryParser.MAX_DEPTH / 2) + "a"
                + ")".repeat(SearchQueryParser.MAX_DEPTH / 2))).isInstanceOf(SearchQueryParser.Not.class);
        assertThatThrownBy(() -> SearchQueryParser.parse("!(".repeat(SearchQueryParser.MAX_DEPTH / 2) + "!a"
                + ")".repeat(SearchQueryParser.MAX_DEPTH / 2)))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("nested");
    }

    @Test
    void countsMixedQuotedFragmentsTowardTheTokenLimit() {
        String withinLimit = "a\"b\"".repeat(SearchQueryParser.MAX_TOKENS / 2);
        assertThat(SearchQueryParser.parse(withinLimit)).isInstanceOf(SearchQueryParser.Term.class);
        assertThatThrownBy(() -> SearchQueryParser.parse(withinLimit + "a"))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("tokens");
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
