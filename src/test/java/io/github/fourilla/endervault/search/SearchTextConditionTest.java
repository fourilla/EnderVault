package io.github.fourilla.endervault.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchTextConditionTest {

    @Test
    void preservesCaseInsensitiveContainsAndEqualityDefaults() {
        SearchTextCondition contains = condition("name:Report", SearchTextCondition.Mode.CONTAINS);
        SearchTextCondition equals = condition("name:Report", SearchTextCondition.Mode.EQUALS);

        assertThat(contains.mode()).isEqualTo(SearchTextCondition.Mode.CONTAINS);
        assertThat(contains.test("Annual REPORT.pdf")).isTrue();
        assertThat(contains.test("rep ort")).isFalse();
        assertThat(equals.mode()).isEqualTo(SearchTextCondition.Mode.EQUALS);
        assertThat(equals.test("REPORT")).isTrue();
        assertThat(equals.test("Annual REPORT.pdf")).isFalse();
        assertThat(contains.test(null)).isFalse();
        assertThat(equals.test(null)).isFalse();
    }

    @Test
    void onlyAnUnquotedEqualsPrefixOnAFieldSelectsExactText() {
        SearchTextCondition exact = condition("name:=report", SearchTextCondition.Mode.CONTAINS);
        assertThat(exact.mode()).isEqualTo(SearchTextCondition.Mode.EQUALS);
        assertThat(exact.test("REPORT")).isTrue();
        assertThat(exact.test("annual report")).isFalse();

        SearchTextCondition literal = condition("name:\"=report\"", SearchTextCondition.Mode.CONTAINS);
        assertThat(literal.test("annual =REPORT")).isTrue();
        assertThat(literal.test("report")).isFalse();
        assertThat(condition("=report", SearchTextCondition.Mode.CONTAINS).test("annual =REPORT"))
                .isTrue();
        assertThat(condition("name:=\"annual report.pdf\"", SearchTextCondition.Mode.CONTAINS)
                .test("ANNUAL REPORT.PDF")).isTrue();
        assertThat(condition("name:\"\"=report", SearchTextCondition.Mode.CONTAINS).test("REPORT")).isTrue();
        assertThat(condition("name:\"\"=report", SearchTextCondition.Mode.CONTAINS).test("annual report")).isFalse();
        assertThat(condition("name: !report", SearchTextCondition.Mode.CONTAINS).test("annual !REPORT")).isTrue();
        assertThatThrownBy(() -> condition("name:\"\"=report*", SearchTextCondition.Mode.CONTAINS))
                .isInstanceOf(SearchQueryException.class).hasMessageContaining("Exact text search");
    }

    @Test
    void quotesProtectWildcardCharactersWithoutChangingTheContainsDefault() {
        SearchTextCondition literal = condition("name:\"a*b?\"", SearchTextCondition.Mode.CONTAINS);
        assertThat(literal.mode()).isEqualTo(SearchTextCondition.Mode.CONTAINS);
        assertThat(literal.test("before a*b? after")).isTrue();
        assertThat(literal.test("before axxbz after")).isFalse();
        assertThat(condition("name:=\"report*\"", SearchTextCondition.Mode.CONTAINS)
                .test("REPORT*")).isTrue();
        assertThat(condition("name:=\"report*\"", SearchTextCondition.Mode.CONTAINS)
                .test("report2")).isFalse();
        assertThat(condition("name:\">report\"", SearchTextCondition.Mode.CONTAINS)
                .test("a >REPORT b")).isTrue();
    }

    @Test
    void mixedQuotesKeepLiteralAndActiveWildcardsDistinct() {
        SearchTextCondition annual = condition("name:\"annual report\"*.pdf", SearchTextCondition.Mode.CONTAINS);
        assertThat(annual.mode()).isEqualTo(SearchTextCondition.Mode.GLOB);
        assertThat(annual.test("ANNUAL REPORT.pdf")).isTrue();
        assertThat(annual.test("annual report 2026.pdf")).isTrue();
        assertThat(annual.test("other annual report 2026.pdf")).isFalse();
        assertThat(annual.test("annual report 2026.pdf.bak")).isFalse();

        SearchTextCondition literalStar = condition("name:*\"a*b\"*", SearchTextCondition.Mode.CONTAINS);
        assertThat(literalStar.test("before a*b after")).isTrue();
        assertThat(literalStar.test("before axxb after")).isFalse();
        assertThat(condition("name:a\"*\"?", SearchTextCondition.Mode.CONTAINS).test("a*x")).isTrue();
        assertThat(condition("name:a\"*\"?", SearchTextCondition.Mode.CONTAINS).test("axx")).isFalse();
    }

    @Test
    void globMatchesTheWholeValueEvenForAnEqualityDefaultField() {
        SearchTextCondition pattern = condition("extension:p?f", SearchTextCondition.Mode.EQUALS);
        assertThat(pattern.mode()).isEqualTo(SearchTextCondition.Mode.GLOB);
        assertThat(pattern.test("PDF")).isTrue();
        assertThat(pattern.test("pdf.bak")).isFalse();
        assertThat(pattern.test("apdf")).isFalse();
        assertThat(pattern.test(null)).isFalse();
    }

    @Test
    void starCanCrossDirectorySeparatorsAndMatchAnEmptyString() {
        SearchTextCondition path = condition("path:docs/*.md", SearchTextCondition.Mode.CONTAINS);
        assertThat(path.test("docs/README.md")).isTrue();
        assertThat(path.test("docs/nested/README.md")).isTrue();
        assertThat(path.test("docs/.md")).isTrue();
        assertThat(path.test("other/docs/README.md")).isFalse();

        SearchTextCondition all = condition("name:***", SearchTextCondition.Mode.CONTAINS);
        assertThat(all.test("")).isTrue();
        assertThat(all.test("photos/2026/사진 📷")).isTrue();
        assertThat(all.test(null)).isFalse();
    }

    @Test
    void questionCountsUnicodeCodePointsRatherThanUtf16UnitsOrGraphemes() {
        SearchTextCondition one = condition("name:?", SearchTextCondition.Mode.CONTAINS);
        assertThat(one.test("📷")).isTrue();
        assertThat(one.test("가")).isTrue();
        assertThat(one.test("ab")).isFalse();
        assertThat(one.test("e\u0301")).isFalse();
        assertThat(condition("name:photo?.jpg", SearchTextCondition.Mode.CONTAINS)
                .test("photo📷.jpg")).isTrue();
    }

    @Test
    void caseExpansionHappensBeforeWildcardCharacterCounting() {
        assertThat(condition("name:?", SearchTextCondition.Mode.CONTAINS).test("İ")).isFalse();
        assertThat(condition("name:??", SearchTextCondition.Mode.CONTAINS).test("İ")).isTrue();
        assertThat(condition("name:i?", SearchTextCondition.Mode.CONTAINS).test("İ")).isTrue();
        assertThat(condition("name:İ?", SearchTextCondition.Mode.CONTAINS).test("İa")).isTrue();
        assertThat(condition("name:İ", SearchTextCondition.Mode.EQUALS).test("i\u0307")).isTrue();
    }

    @Test
    void adjacentQuotedAndUnquotedLiteralFragmentsShareTheSameCaseContext() {
        assertThat(condition("name:\"Ο\"Σ", SearchTextCondition.Mode.EQUALS).test("ΟΣ")).isTrue();
        assertThat(condition("name:\"Ο\"Σ?", SearchTextCondition.Mode.CONTAINS).test("ΟΣ!")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name:<report", "name:>=report", "name:!=report", "name:=report*",
            "name:=report?", "name:=\"annual report\"*.pdf", "name:=", "name:=\"\""
    })
    void rejectsUnsupportedOrAmbiguousOperatorsAtTheRawTermPosition(String query) {
        assertThatThrownBy(() -> condition("  " + query, SearchTextCondition.Mode.CONTAINS))
                .isInstanceOfSatisfying(SearchQueryException.class,
                        exception -> assertThat(exception.position()).isEqualTo(2));
    }

    @Test
    void repeatedStarsAndFailingSuffixesHavePredictableMatchingWithoutRegexRecursion() {
        String candidate = "a".repeat(5000);
        assertThat(condition("name:" + "*a".repeat(1000) + "b", SearchTextCondition.Mode.CONTAINS)
                .test(candidate)).isFalse();
        assertThat(condition("name:*" + "a".repeat(1000) + "b", SearchTextCondition.Mode.CONTAINS)
                .test(candidate)).isFalse();
        assertThat(condition("name:" + "*".repeat(2000) + "a", SearchTextCondition.Mode.CONTAINS)
                .test(candidate)).isTrue();
    }

    @Test
    void randomMixedLiteralAndWildcardPatternsAgreeWithAnIndependentDynamicProgrammingMatcher() {
        Random random = new Random(20261013);
        int[] literals = {'a', 'B', '가', 0x1F4F7, '*', '?', '/'};
        for (int attempt = 0; attempt < 1000; attempt++) {
            StringBuilder query = new StringBuilder("name:*");
            List<Integer> reference = new ArrayList<>(List.of(-1));
            int count = 1 + random.nextInt(20);
            for (int index = 0; index < count; index++) {
                switch (random.nextInt(4)) {
                    case 0 -> {
                        query.append('*');
                        reference.add(-1);
                    }
                    case 1 -> {
                        query.append('?');
                        reference.add(-2);
                    }
                    default -> {
                        int literal = literals[random.nextInt(literals.length)];
                        String text = new String(Character.toChars(literal));
                        query.append('"').append(text).append('"');
                        text.toLowerCase(Locale.ROOT).codePoints().forEach(reference::add);
                    }
                }
            }
            StringBuilder actual = new StringBuilder();
            int length = random.nextInt(40);
            for (int index = 0; index < length; index++) {
                actual.appendCodePoint(literals[random.nextInt(literals.length)]);
            }
            boolean expected = referenceMatches(reference, actual.toString().toLowerCase(Locale.ROOT));
            assertThat(condition(query.toString(), SearchTextCondition.Mode.CONTAINS).test(actual.toString()))
                    .as("pattern %s against %s", query, actual).isEqualTo(expected);
        }
    }

    private SearchTextCondition condition(String query, SearchTextCondition.Mode mode) {
        return SearchTextCondition.compile((SearchQueryParser.Term) SearchQueryParser.parse(query), mode);
    }

    private boolean referenceMatches(List<Integer> pattern, String actual) {
        int[] input = actual.codePoints().toArray();
        boolean[][] matches = new boolean[pattern.size() + 1][input.length + 1];
        matches[0][0] = true;
        for (int token = 1; token <= pattern.size(); token++) {
            int expected = pattern.get(token - 1);
            for (int character = 0; character <= input.length; character++) {
                matches[token][character] = expected == -1
                        ? matches[token - 1][character] || character > 0 && matches[token][character - 1]
                        : character > 0 && matches[token - 1][character - 1]
                                && (expected == -2 || expected == input[character - 1]);
            }
        }
        return matches[pattern.size()][input.length];
    }
}
