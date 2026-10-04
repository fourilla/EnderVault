package io.github.fourilla.endervault.search;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

final class SearchNumberFilter {
    private static final List<String> BYTE_UNITS = List.of("B", "KB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB");
    private static final Map<String, Long> MULTIPLIERS = multipliers();
    private static final Pattern BYTE_VALUE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([A-Za-z]*)");
    private static final Pattern INTEGER_VALUE = Pattern.compile("-?[0-9]+");

    private SearchNumberFilter() {}

    static List<String> byteUnits() { return BYTE_UNITS; }

    static LongPredicate compile(SearchQueryParser.Term term, boolean bytes) {
        String value = term.value();
        String operator = "";
        for (String candidate : List.of(">=", "<=", ">", "<", "=")) {
            if (value.startsWith(candidate)) {
                operator = candidate;
                value = value.substring(candidate.length());
                break;
            }
        }
        int range = value.indexOf("..");
        if (range >= 0) {
            if (!operator.isEmpty() || value.indexOf("..", range + 2) >= 0) {
                throw invalid(term, "Invalid numeric search range.");
            }
            long lower = boundary(value.substring(0, range), bytes, term);
            long upper = boundary(value.substring(range + 2), bytes, term);
            if (upper < lower) throw invalid(term, "Search range ends before it starts.");
            return actual -> actual >= lower && actual <= upper;
        }
        long expected = boundary(value, bytes, term);
        return switch (operator) {
            case ">=" -> actual -> actual >= expected;
            case ">" -> actual -> actual > expected;
            case "<=" -> actual -> actual <= expected;
            case "<" -> actual -> actual < expected;
            default -> actual -> actual == expected;
        };
    }

    private static long boundary(String value, boolean bytes, SearchQueryParser.Term term) {
        try {
            if (!bytes) {
                if (!INTEGER_VALUE.matcher(value).matches()) throw new NumberFormatException();
                return Long.parseLong(value);
            }
            var parts = BYTE_VALUE.matcher(value);
            if (!parts.matches()) throw new NumberFormatException();
            String unit = parts.group(2).toUpperCase(Locale.ROOT);
            Long multiplier = MULTIPLIERS.get(unit.isEmpty() ? "B" : unit);
            if (multiplier == null) throw new NumberFormatException();
            return new BigDecimal(parts.group(1)).multiply(BigDecimal.valueOf(multiplier)).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw invalid(term, bytes
                    ? "Use a nonnegative whole-byte size within the 64-bit range; supported units: " + String.join(", ", BYTE_UNITS) + "."
                    : "Use a signed 64-bit integer for this search field.");
        }
    }

    private static Map<String, Long> multipliers() {
        var values = new LinkedHashMap<String, Long>();
        values.put("B", 1L);
        for (int power = 1; power <= 4; power++) {
            values.put(BYTE_UNITS.get(power).toUpperCase(Locale.ROOT), BigDecimal.valueOf(1000).pow(power).longValueExact());
            values.put(BYTE_UNITS.get(power + 4).toUpperCase(Locale.ROOT), BigDecimal.valueOf(1024).pow(power).longValueExact());
        }
        return Map.copyOf(values);
    }

    private static SearchQueryException invalid(SearchQueryParser.Term term, String message) {
        return new SearchQueryException(message, term.position());
    }
}
