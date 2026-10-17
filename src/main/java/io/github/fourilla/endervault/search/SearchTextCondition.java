package io.github.fourilla.endervault.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A prepared, case-insensitive text condition shared by domain search matchers. */
public final class SearchTextCondition {

    public enum Mode { CONTAINS, EQUALS, GLOB }

    private static final int STAR = -1;
    private static final int QUESTION = -2;

    private final Mode mode;
    private final String expected;
    private final int[] pattern;

    private SearchTextCondition(Mode mode, String expected, int[] pattern) {
        this.mode = mode;
        this.expected = expected;
        this.pattern = pattern;
    }

    static SearchTextCondition compile(SearchQueryParser.Term term, Mode defaultMode) {
        Objects.requireNonNull(term);
        Objects.requireNonNull(defaultMode);
        boolean exact = term.field() != null && term.hasUnquotedPrefix("=");
        if (term.field() != null) {
            if (term.hasUnquotedPrefix("!=")) {
                throw invalid(term, "Use ! before a search condition instead of !=.");
            }
            if (term.hasUnquotedPrefix(">") || term.hasUnquotedPrefix("<")) {
                throw invalid(term, "Text search supports =, * and ?; ordered comparisons need a numeric or date/time field.");
            }
        }
        if (exact && term.hasWildcards()) {
            throw invalid(term, "Exact text search cannot contain unquoted wildcards. Quote * and ? to search them literally.");
        }
        String value = exact ? term.value().substring(1) : term.value();
        if (value.isBlank()) {
            throw invalid(term, "Search conditions cannot be empty.");
        }
        Mode mode = exact ? Mode.EQUALS : term.hasWildcards() ? Mode.GLOB : defaultMode;
        return new SearchTextCondition(mode, mode == Mode.GLOB ? null : value.toLowerCase(Locale.ROOT),
                mode == Mode.GLOB ? pattern(term.parts()) : null);
    }

    public Mode mode() {
        return mode;
    }

    /** Wildcards count Unicode code points after the same Locale.ROOT fold used by literal search. */
    public boolean test(String actual) {
        if (actual == null) {
            return false;
        }
        String normalized = actual.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case CONTAINS -> normalized.contains(expected);
            case EQUALS -> normalized.equals(expected);
            case GLOB -> matchesGlob(normalized);
        };
    }

    private static int[] pattern(List<SearchQueryParser.ValuePart> parts) {
        List<Integer> result = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (SearchQueryParser.ValuePart part : parts) {
            for (int offset = 0; offset < part.text().length();) {
                int character = part.text().codePointAt(offset);
                offset += Character.charCount(character);
                if (!part.quoted() && (character == '*' || character == '?')) {
                    appendLiteral(result, literal);
                    int wildcard = character == '*' ? STAR : QUESTION;
                    if (wildcard != STAR || result.isEmpty() || result.getLast() != STAR) {
                        result.add(wildcard);
                    }
                } else {
                    literal.appendCodePoint(character);
                }
            }
        }
        appendLiteral(result, literal);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void appendLiteral(List<Integer> result, StringBuilder literal) {
        if (!literal.isEmpty()) {
            // Join adjacent quote fragments before folding: Greek final sigma depends on context.
            literal.toString().toLowerCase(Locale.ROOT).codePoints().forEach(result::add);
            literal.setLength(0);
        }
    }

    private boolean matchesGlob(String actual) {
        // Only the latest star is retried. Unlike regex recursion, work is bounded by
        // candidate code points times prepared pattern length, with constant match memory.
        int input = 0;
        int token = 0;
        int star = -1;
        int retry = -1;
        while (input < actual.length()) {
            int character = actual.codePointAt(input);
            if (token < pattern.length && (pattern[token] == QUESTION || pattern[token] == character)) {
                token++;
                input += Character.charCount(character);
            } else if (token < pattern.length && pattern[token] == STAR) {
                star = token++;
                retry = input;
            } else if (star >= 0) {
                token = star + 1;
                retry += Character.charCount(actual.codePointAt(retry));
                input = retry;
            } else {
                return false;
            }
        }
        while (token < pattern.length && pattern[token] == STAR) {
            token++;
        }
        return token == pattern.length;
    }

    private static SearchQueryException invalid(SearchQueryParser.Term term, String message) {
        return new SearchQueryException(message, term.position());
    }
}
