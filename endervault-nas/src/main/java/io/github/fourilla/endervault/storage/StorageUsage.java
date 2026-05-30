package io.github.fourilla.endervault.storage;

public record StorageUsage(
        long usedBytes,
        long totalBytes,
        long usableBytes,
        String usedLabel,
        String totalLabel,
        String usableLabel,
        int usedPercent
) {
}
