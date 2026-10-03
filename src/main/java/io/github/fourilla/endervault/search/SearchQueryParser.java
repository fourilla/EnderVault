package io.github.fourilla.endervault.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SearchQueryParser {

    static final int MAX_LENGTH = 4096;
    static final int MAX_TOKENS = 256;
    static final int MAX_TERMS = 128;
    static final int MAX_DEPTH = 16;

    sealed interface Expression permits All, Term, And, Or {}

    record All() implements Expression {}

    record Term(String field, String value, boolean quoted, int position) implements Expression {}

    record And(List<Expression> terms) implements Expression {}

    record Or(List<Expression> terms) implements Expression {}

    private enum Kind { WORD, QUOTED, AND, OR, OPEN, CLOSE, END }

    private record Token(Kind kind, String value, int start, int end) {}

    private final List<Token> tokens;
    private int cursor;
    private int termCount;

    private SearchQueryParser(String query) {
        tokens = tokenize(query);
    }

    static Expression parse(String query) {
        String input = query == null ? "" : query;
        if (input.length() > MAX_LENGTH) {
            throw new SearchQueryException("Search query is too long.", MAX_LENGTH);
        }
        SearchQueryParser parser = new SearchQueryParser(input);
        if (parser.peek().kind() == Kind.END) {
            return new All();
        }
        Expression result = parser.parseOr(0);
        if (parser.peek().kind() != Kind.END) {
            throw parser.error("Unexpected search token.");
        }
        return result;
    }

    static boolean isFieldName(String value) {
        if (value.isEmpty() || value.length() > 32 || !isAsciiLetter(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetter(character) && !(character >= '0' && character <= '9')
                    && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z';
    }

    private Expression parseOr(int depth) {
        List<Expression> parts = new ArrayList<>();
        parts.add(parseAnd(depth));
        while (peek().kind() == Kind.OR) {
            take();
            parts.add(parseAnd(depth));
        }
        return parts.size() == 1 ? parts.getFirst() : new Or(List.copyOf(parts));
    }

    private Expression parseAnd(int depth) {
        List<Expression> parts = new ArrayList<>();
        parts.add(parseAtom(depth));
        while (true) {
            if (peek().kind() == Kind.AND) {
                take();
                parts.add(parseAtom(depth));
            } else if (startsAtom(peek().kind())) {
                if (tokens.get(cursor - 1).end() == peek().start()) {
                    throw error("Separate search conditions with whitespace or an operator.");
                }
                parts.add(parseAtom(depth));
            } else {
                break;
            }
        }
        return parts.size() == 1 ? parts.getFirst() : new And(List.copyOf(parts));
    }

    private Expression parseAtom(int depth) {
        if (peek().kind() == Kind.OPEN) {
            if (depth >= MAX_DEPTH) {
                throw error("Search groups are nested too deeply.");
            }
            take();
            Expression result = parseOr(depth + 1);
            if (peek().kind() != Kind.CLOSE) {
                throw error("Missing closing search parenthesis.");
            }
            take();
            return result;
        }
        if (peek().kind() != Kind.WORD && peek().kind() != Kind.QUOTED) {
            throw error("Expected a search condition.");
        }
        Token token = take();
        String field = null;
        String value = token.value();
        boolean quoted = token.kind() == Kind.QUOTED;
        int colon = value.indexOf(':');
        if (!quoted && colon > 0 && isFieldName(value.substring(0, colon))) {
            String suffix = value.substring(colon + 1);
            // Scheme URLs and drive paths are literal terms, not search field names.
            boolean literalPath = suffix.startsWith("//")
                    || colon == 1 && (suffix.startsWith("\\") || suffix.startsWith("/"));
            if (!literalPath) {
                field = value.substring(0, colon).toLowerCase(Locale.ROOT);
                value = suffix;
                if (value.isEmpty()) {
                    if (peek().kind() != Kind.WORD && peek().kind() != Kind.QUOTED) {
                        throw error("A search field needs a value.");
                    }
                    Token fieldValue = take();
                    value = fieldValue.value();
                    quoted = fieldValue.kind() == Kind.QUOTED;
                }
            }
        }
        if (value.isBlank()) {
            throw new SearchQueryException("Search conditions cannot be empty.", token.start());
        }
        if (++termCount > MAX_TERMS) {
            throw new SearchQueryException("Too many search conditions.", token.start());
        }
        return new Term(field, value, quoted, token.start());
    }

    private static boolean startsAtom(Kind kind) {
        return kind == Kind.WORD || kind == Kind.QUOTED || kind == Kind.OPEN;
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token take() {
        return tokens.get(cursor++);
    }

    private SearchQueryException error(String message) {
        return new SearchQueryException(message, peek().start());
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            char character = input.charAt(index);
            if (Character.isWhitespace(character)) {
                index++;
                continue;
            }
            int start = index;
            Kind kind;
            String value;
            if (character == '(' || character == ')') {
                kind = character == '(' ? Kind.OPEN : Kind.CLOSE;
                value = input.substring(index, ++index);
            } else if (isOperatorAt(input, index)) {
                kind = character == '&' ? Kind.AND : Kind.OR;
                index += 2;
                value = input.substring(start, index);
            } else if (character == '"') {
                kind = Kind.QUOTED;
                StringBuilder text = new StringBuilder();
                index++;
                boolean closed = false;
                while (index < input.length()) {
                    char next = input.charAt(index++);
                    if (next == '"') {
                        closed = true;
                        break;
                    }
                    if (next == '\\' && index < input.length()
                            && (input.charAt(index) == '"' || input.charAt(index) == '\\')) {
                        next = input.charAt(index++);
                    }
                    text.append(next);
                }
                if (!closed) {
                    throw new SearchQueryException("Unclosed search quote.", start);
                }
                value = text.toString();
            } else {
                kind = Kind.WORD;
                while (index < input.length()) {
                    char next = input.charAt(index);
                    if (Character.isWhitespace(next) || next == '(' || next == ')' || next == '"'
                            || isOperatorAt(input, index)) {
                        break;
                    }
                    index++;
                }
                value = input.substring(start, index);
            }
            if (tokens.size() >= MAX_TOKENS) {
                throw new SearchQueryException("Too many search tokens.", start);
            }
            tokens.add(new Token(kind, value, start, index));
        }
        tokens.add(new Token(Kind.END, "", input.length(), input.length()));
        return List.copyOf(tokens);
    }

    private static boolean isOperatorAt(String input, int index) {
        char character = input.charAt(index);
        return (character == '&' || character == '|')
                && index + 1 < input.length() && input.charAt(index + 1) == character;
    }
}
