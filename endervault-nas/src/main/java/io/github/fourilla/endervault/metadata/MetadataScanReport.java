package io.github.fourilla.endervault.metadata;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record MetadataScanReport(
        Instant scannedAt,
        List<MetadataAreaReport> areaReports
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public int issueCount() {
        return areaReports.stream().mapToInt(MetadataAreaReport::issueCount).sum();
    }

    public int repairableCount() {
        return areaReports.stream().mapToInt(MetadataAreaReport::repairableCount).sum();
    }

    public boolean healthy() {
        return issueCount() == 0;
    }

    public String scannedAtLabel() {
        return LABEL_FORMATTER.format(scannedAt);
    }
}
