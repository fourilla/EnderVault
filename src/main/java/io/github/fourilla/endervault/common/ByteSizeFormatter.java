package io.github.fourilla.endervault.common;

public final class ByteSizeFormatter {

    private ByteSizeFormatter() {
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        do {
            value = value / 1024;
            unitIndex++;
        } while (value >= 1024 && unitIndex < units.length - 1);
        return "%.1f %s".formatted(value, units[unitIndex]);
    }
}
