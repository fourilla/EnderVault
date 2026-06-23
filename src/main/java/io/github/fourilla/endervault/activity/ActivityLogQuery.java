package io.github.fourilla.endervault.activity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public record ActivityLogQuery(
        String text,
        String type,
        String status,
        String order,
        String from,
        String to,
        int page,
        int size
) {
    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 1000;

    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SECOND_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public ActivityLogQuery {
        text = normalizeText(text);
        type = normalizeType(type);
        status = normalizeChoice(status, "all", "success", "failed");
        order = normalizeChoice(order, "newest", "oldest");
        from = normalizeDateTime(from);
        to = normalizeDateTime(to);
        page = normalizePage(page);
        size = normalizeSize(size);
    }

    public boolean newestFirst() {
        return "newest".equals(order);
    }

    public boolean matches(ActivityLogEntry entry) {
        return matchesType(entry) && matchesStatus(entry) && matchesDate(entry) && matchesText(entry);
    }

    private boolean matchesType(ActivityLogEntry entry) {
        return type.isBlank() || type.equals(entry.safeType());
    }

    private boolean matchesStatus(ActivityLogEntry entry) {
        return "all".equals(status)
                || ("success".equals(status) && entry.success())
                || ("failed".equals(status) && !entry.success());
    }

    private boolean matchesText(ActivityLogEntry entry) {
        return text.isBlank()
                || entry.searchText().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private boolean matchesDate(ActivityLogEntry entry) {
        Instant timestamp = entry.timestampForSort();
        Instant fromInstant = parseDateTime(from, false);
        if (fromInstant != null && timestamp.isBefore(fromInstant)) {
            return false;
        }

        Instant toInstant = parseDateTime(to, true);
        return toInstant == null || !timestamp.isAfter(toInstant);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeChoice(String value, String fallback, String... allowed) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (fallback.equals(normalized)) {
            return fallback;
        }

        for (String option : allowed) {
            if (option.equals(normalized)) {
                return normalized;
            }
        }
        return fallback;
    }

    private static String normalizeDateTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return parseLocalDateTime(trimmed) == null ? "" : trimmed;
    }

    private static int normalizePage(int value) {
        return Math.max(1, value);
    }

    private static int normalizeSize(int value) {
        if (value <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(value, MAX_SIZE);
    }

    private static Instant parseDateTime(String value, boolean endOfMinute) {
        LocalDateTime localDateTime = parseLocalDateTime(value);
        if (localDateTime == null) {
            return null;
        }
        if (endOfMinute && value.length() == 16) {
            localDateTime = localDateTime.plusSeconds(59).plusNanos(999_999_999);
        }
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static LocalDateTime parseLocalDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, value.length() == 16 ? MINUTE_FORMATTER : SECOND_FORMATTER);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
