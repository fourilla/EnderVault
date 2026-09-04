package io.github.fourilla.endervault.remote;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

record RemoteContentRange(long start, long end, long total) {

    private static final Pattern SATISFIED = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSATISFIED = Pattern.compile("bytes\\s+\\*/(\\d+)", Pattern.CASE_INSENSITIVE);

    static RemoteContentRange parse(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = SATISFIED.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            if (start < 0L || end < start || total <= end) {
                return null;
            }
            return new RemoteContentRange(start, end, total);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static long unsatisfiedTotal(String value) {
        if (value == null) {
            return -1L;
        }
        Matcher matcher = UNSATISFIED.matcher(value.trim());
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    boolean matches(RemoteByteRange range, long expectedTotal) {
        return start == range.start() && end == range.end() && total == expectedTotal;
    }
}
