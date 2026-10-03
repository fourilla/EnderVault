package io.github.fourilla.endervault.search;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/** Compiles a bounded query against explicitly registered fields, without fetching data. */
public final class SearchSchema<T> {

    public enum ValueType { TEXT, PATH, ENUM, DATE_TIME, NUMBER }

    public enum Operator { CONTAINS, EQUALS, BEFORE, BEFORE_OR_ON, AFTER, AFTER_OR_ON, RANGE,
        LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL }

    public record QueryLimits(int maxLength, int maxTokens, int maxTerms, int maxDepth) {}

    public static QueryLimits limits() {
        return new QueryLimits(SearchQueryParser.MAX_LENGTH, SearchQueryParser.MAX_TOKENS,
                SearchQueryParser.MAX_TERMS, SearchQueryParser.MAX_DEPTH);
    }

    public record FieldInfo(
            String key,
            String label,
            ValueType type,
            List<Operator> operators,
            List<String> values,
            String timeZone,
            List<String> units
    ) {
        public FieldInfo {
            operators = List.copyOf(operators);
            values = List.copyOf(values);
            units = List.copyOf(units);
        }
    }

    public static final class Field<T> {
        private final FieldInfo info;
        private final Function<SearchQueryParser.Term, Predicate<T>> compiler;

        private Field(FieldInfo info, Function<SearchQueryParser.Term, Predicate<T>> compiler) {
            this.info = info;
            this.compiler = compiler;
        }
    }

    private final BiPredicate<T, String> defaultMatcher;
    private final Map<String, Field<T>> fields;
    private final List<FieldInfo> fieldInfo;

    /** The default matcher receives a Locale.ROOT lowercase term. */
    public SearchSchema(BiPredicate<T, String> defaultMatcher, List<Field<T>> fields) {
        this.defaultMatcher = Objects.requireNonNull(defaultMatcher);
        Map<String, Field<T>> registered = new LinkedHashMap<>();
        for (Field<T> field : fields) {
            if (registered.putIfAbsent(field.info.key(), field) != null) {
                throw new IllegalArgumentException("Duplicate search field: " + field.info.key());
            }
        }
        this.fields = Map.copyOf(registered);
        this.fieldInfo = fields.stream().map(field -> field.info).toList();
    }

    /** Only schema metadata is exposed; extractors remain on the server. */
    public List<FieldInfo> fields() {
        return fieldInfo;
    }

    /** Compile once, then evaluate only candidates already authorized by the domain. */
    public Predicate<T> compile(String query) {
        return compile(SearchQueryParser.parse(query));
    }

    private Predicate<T> compile(SearchQueryParser.Expression expression) {
        return switch (expression) {
            case SearchQueryParser.All ignored -> item -> true;
            case SearchQueryParser.Term term -> compileTerm(term);
            case SearchQueryParser.And and -> {
                List<Predicate<T>> parts = and.terms().stream().map(this::compile).toList();
                yield item -> {
                    for (Predicate<T> part : parts) {
                        if (!part.test(item)) {
                            return false;
                        }
                    }
                    return true;
                };
            }
            case SearchQueryParser.Or or -> {
                List<Predicate<T>> parts = or.terms().stream().map(this::compile).toList();
                yield item -> {
                    for (Predicate<T> part : parts) {
                        if (part.test(item)) {
                            return true;
                        }
                    }
                    return false;
                };
            }
        };
    }

    private Predicate<T> compileTerm(SearchQueryParser.Term term) {
        if (term.field() == null) {
            String value = term.value().toLowerCase(Locale.ROOT);
            return item -> defaultMatcher.test(item, value);
        }
        Field<T> field = fields.get(term.field());
        if (field == null) {
            throw invalid(term, "Unknown search field: " + term.field());
        }
        return field.compiler.apply(term);
    }

    public static <T> Field<T> text(String key, String label, Function<T, String> extractor) {
        return containing(key, label, ValueType.TEXT, extractor);
    }

    public static <T> Field<T> path(String key, String label, Function<T, String> extractor) {
        return containing(key, label, ValueType.PATH, extractor);
    }

    public static <T> Field<T> exactText(String key, String label, Function<T, String> extractor) {
        Objects.requireNonNull(extractor);
        return new Field<>(info(key, label, ValueType.TEXT, List.of(Operator.EQUALS), List.of(), null), term -> {
            requireLiteralOperator(term);
            String expected = term.value().toLowerCase(Locale.ROOT);
            return item -> {
                String actual = extractor.apply(item);
                return actual != null && actual.toLowerCase(Locale.ROOT).equals(expected);
            };
        });
    }

    public static <T> Field<T> number(String key, String label, Function<T, Long> extractor) {
        return numeric(key, label, extractor, false);
    }

    public static <T> Field<T> byteSize(String key, String label, Function<T, Long> extractor) {
        return numeric(key, label, extractor, true);
    }

    private static <T> Field<T> numeric(String key, String label, Function<T, Long> extractor, boolean bytes) {
        Objects.requireNonNull(extractor);
        FieldInfo base = info(key, label, ValueType.NUMBER, List.of(Operator.EQUALS, Operator.LESS_THAN,
                Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN, Operator.GREATER_THAN_OR_EQUAL, Operator.RANGE),
                List.of(), null);
        FieldInfo metadata = new FieldInfo(base.key(), base.label(), base.type(), base.operators(), base.values(),
                base.timeZone(), bytes ? SearchNumberFilter.byteUnits() : List.of());
        return new Field<>(metadata, term -> {
            var filter = SearchNumberFilter.compile(term, bytes);
            return item -> {
                Long actual = extractor.apply(item);
                return actual != null && (!bytes || actual >= 0) && filter.test(actual);
            };
        });
    }

    private static <T> Field<T> containing(
            String key, String label, ValueType type, Function<T, String> extractor
    ) {
        Objects.requireNonNull(extractor);
        return new Field<>(info(key, label, type, List.of(Operator.CONTAINS), List.of(), null), term -> {
            requireLiteralOperator(term);
            String expected = term.value().toLowerCase(Locale.ROOT);
            return item -> {
                String actual = extractor.apply(item);
                return actual != null && actual.toLowerCase(Locale.ROOT).contains(expected);
            };
        });
    }

    public static <T> Field<T> enumeration(
            String key, String label, List<String> values, Function<T, String> extractor
    ) {
        Objects.requireNonNull(extractor);
        Map<String, String> allowed = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()
                    || allowed.putIfAbsent(value.toLowerCase(Locale.ROOT), value) != null) {
                throw new IllegalArgumentException("Enum search values must be nonempty and unique.");
            }
        }
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("An enum search field needs allowed values.");
        }
        return new Field<>(info(key, label, ValueType.ENUM, List.of(Operator.EQUALS),
                List.copyOf(allowed.values()), null), term -> {
            requireLiteralOperator(term);
            String expected = term.value().toLowerCase(Locale.ROOT);
            if (!allowed.containsKey(expected)) {
                throw invalid(term, "Invalid value for search field: " + term.field());
            }
            return item -> {
                String actual = extractor.apply(item);
                return actual != null && actual.toLowerCase(Locale.ROOT).equals(expected);
            };
        });
    }

    public static <T> Field<T> dateTime(
            String key, String label, ZoneId zone, Function<T, Instant> extractor
    ) {
        Objects.requireNonNull(zone);
        Objects.requireNonNull(extractor);
        return new Field<>(info(key, label, ValueType.DATE_TIME,
                List.of(Operator.EQUALS, Operator.BEFORE, Operator.BEFORE_OR_ON,
                        Operator.AFTER, Operator.AFTER_OR_ON, Operator.RANGE),
                List.of(), zone.getId()), term -> {
            Predicate<Instant> filter = dateFilter(term, zone);
            return item -> {
                Instant actual = extractor.apply(item);
                return actual != null && filter.test(actual);
            };
        });
    }

    private static FieldInfo info(
            String key, String label, ValueType type, List<Operator> operators,
            List<String> values, String timeZone
    ) {
        if (key == null || !SearchQueryParser.isFieldName(key) || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Search fields need a valid key and label.");
        }
        return new FieldInfo(key.toLowerCase(Locale.ROOT), label, type, operators, values, timeZone, List.of());
    }

    private static void requireLiteralOperator(SearchQueryParser.Term term) {
        if (!term.quoted() && (term.value().startsWith(">") || term.value().startsWith("<")
                || term.value().startsWith("="))) {
            throw invalid(term, "Comparison operators require a date/time or numeric search field.");
        }
    }

    private record DateBoundary(Instant start, Instant endExclusive) {}

    private static Predicate<Instant> dateFilter(SearchQueryParser.Term term, ZoneId zone) {
        String value = term.value();
        String operator = "";
        for (String candidate : List.of(">=", "<=", ">", "<")) {
            if (value.startsWith(candidate)) {
                operator = candidate;
                value = value.substring(candidate.length());
                break;
            }
        }
        int range = value.indexOf("..");
        if (range >= 0) {
            if (!operator.isEmpty() || value.indexOf("..", range + 2) >= 0) {
                throw invalid(term, "Invalid date/time search range.");
            }
            DateBoundary lower = dateBoundary(value.substring(0, range), zone, term);
            DateBoundary upper = dateBoundary(value.substring(range + 2), zone, term);
            if (upper.endExclusive() == null ? upper.start().isBefore(lower.start())
                    : !upper.endExclusive().isAfter(lower.start())) {
                throw invalid(term, "Search range ends before it starts.");
            }
            return actual -> !actual.isBefore(lower.start())
                    && (upper.endExclusive() == null ? !actual.isAfter(upper.start())
                            : actual.isBefore(upper.endExclusive()));
        }
        DateBoundary boundary = dateBoundary(value, zone, term);
        return switch (operator) {
            case ">=" -> actual -> !actual.isBefore(boundary.start());
            case "<" -> actual -> actual.isBefore(boundary.start());
            case ">" -> boundary.endExclusive() == null
                    ? actual -> actual.isAfter(boundary.start())
                    : actual -> !actual.isBefore(boundary.endExclusive());
            case "<=" -> boundary.endExclusive() == null
                    ? actual -> !actual.isAfter(boundary.start())
                    : actual -> actual.isBefore(boundary.endExclusive());
            default -> boundary.endExclusive() == null
                    ? actual -> actual.equals(boundary.start())
                    : actual -> !actual.isBefore(boundary.start()) && actual.isBefore(boundary.endExclusive());
        };
    }

    private static DateBoundary dateBoundary(String value, ZoneId zone, SearchQueryParser.Term term) {
        try {
            if (!value.contains("T")) {
                LocalDate date = LocalDate.parse(value);
                return new DateBoundary(date.atStartOfDay(zone).toInstant(),
                        date.plusDays(1).atStartOfDay(zone).toInstant());
            }
            return new DateBoundary(OffsetDateTime.parse(value).toInstant(), null);
        } catch (DateTimeException ex) {
            throw invalid(term, "Use an ISO date or a date/time with an explicit UTC offset.");
        }
    }

    private static SearchQueryException invalid(SearchQueryParser.Term term, String message) {
        return new SearchQueryException(message, term.position());
    }
}
