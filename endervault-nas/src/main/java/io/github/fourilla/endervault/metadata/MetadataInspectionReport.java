package io.github.fourilla.endervault.metadata;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public record MetadataInspectionReport(
        Instant createdAt,
        List<MetadataArea> selectedAreas,
        MetadataScanReport scanReport
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public MetadataInspectionReport {
        selectedAreas = selectedAreas == null ? List.of() : List.copyOf(selectedAreas);
    }

    public static MetadataInspectionReport empty() {
        return new MetadataInspectionReport(null, List.of(), null);
    }

    public static MetadataInspectionReport from(List<MetadataArea> selectedAreas, MetadataScanReport scanReport) {
        return new MetadataInspectionReport(Instant.now(), List.copyOf(selectedAreas), scanReport);
    }

    public boolean present() {
        return scanReport != null;
    }

    public String createdAtLabel() {
        return createdAt == null ? "-" : LABEL_FORMATTER.format(createdAt);
    }

    public List<String> selectedAreaNames() {
        return selectedAreas == null ? List.of() : selectedAreas.stream().map(MetadataArea::name).toList();
    }

    public MetadataInspectionReport withoutIssueTokens(Collection<String> tokens) {
        if (!present() || tokens == null || tokens.isEmpty()) {
            return this;
        }

        Set<String> tokenSet = Set.copyOf(tokens);
        List<MetadataAreaReport> filteredReports = scanReport.areaReports().stream()
                .map(areaReport -> new MetadataAreaReport(
                        areaReport.area(),
                        areaReport.issues().stream()
                                .filter(issue -> !tokenSet.contains(issue.token()))
                                .toList()
                ))
                .toList();
        return new MetadataInspectionReport(
                Instant.now(),
                selectedAreas,
                new MetadataScanReport(scanReport.scannedAt(), filteredReports)
        );
    }
}
