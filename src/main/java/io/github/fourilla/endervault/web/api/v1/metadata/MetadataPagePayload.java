package io.github.fourilla.endervault.web.api.v1.metadata;

import io.github.fourilla.endervault.metadata.MetadataArea;
import io.github.fourilla.endervault.metadata.MetadataAreaReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataIssue;
import io.github.fourilla.endervault.web.task.TaskPayload;
import java.util.List;

public record MetadataPagePayload(
        List<MetadataAreaPayload> areas,
        MetadataReportPayload report,
        TaskPayload activeInspectionTask
) {

    public record MetadataAreaPayload(
            String name,
            String label,
            String description,
            String iconClass
    ) {
        static MetadataAreaPayload from(MetadataArea area) {
            return new MetadataAreaPayload(area.name(), area.label(), area.description(), area.iconClass());
        }
    }

    public record MetadataReportPayload(
            String createdAtLabel,
            String scannedAtLabel,
            int selectedAreaCount,
            int issueCount,
            int repairableCount,
            boolean healthy,
            List<MetadataAreaReportPayload> areaReports
    ) {
        static MetadataReportPayload from(MetadataInspectionReport report) {
            if (report == null || !report.present()) {
                return null;
            }
            return new MetadataReportPayload(
                    report.createdAtLabel(),
                    report.scanReport().scannedAtLabel(),
                    report.selectedAreas().size(),
                    report.scanReport().issueCount(),
                    report.scanReport().repairableCount(),
                    report.scanReport().healthy(),
                    report.scanReport().areaReports().stream()
                            .map(MetadataAreaReportPayload::from)
                            .toList()
            );
        }
    }

    public record MetadataAreaReportPayload(
            MetadataAreaPayload area,
            int issueCount,
            int repairableCount,
            boolean healthy,
            List<MetadataIssuePayload> issues
    ) {
        static MetadataAreaReportPayload from(MetadataAreaReport report) {
            return new MetadataAreaReportPayload(
                    MetadataAreaPayload.from(report.area()),
                    report.issueCount(),
                    report.repairableCount(),
                    report.healthy(),
                    report.issues().stream().map(MetadataIssuePayload::from).toList()
            );
        }
    }

    public record MetadataIssuePayload(
            String token,
            String severityLabel,
            String severityClass,
            String title,
            String detail,
            String recommendation,
            String actionLabel,
            boolean repairable
    ) {
        static MetadataIssuePayload from(MetadataIssue issue) {
            return new MetadataIssuePayload(
                    issue.token(),
                    issue.severity().label(),
                    issue.severity().cssClass(),
                    issue.title(),
                    issue.detail(),
                    issue.recommendation(),
                    issue.action().label(),
                    issue.repairable()
            );
        }
    }
}
