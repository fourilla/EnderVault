package io.github.fourilla.endervault.web.api.v1.metadata;

import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService.QueuedInspection;
import io.github.fourilla.endervault.metadata.MetadataIssue;
import io.github.fourilla.endervault.metadata.MetadataMaintenanceService;
import io.github.fourilla.endervault.metadata.MetadataRepairSummary;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metadata")
public class MetadataApiController {

    private final MetadataMaintenanceService metadataMaintenanceService;
    private final MetadataInspectionTaskService metadataInspectionTaskService;
    private final MetadataInspectionReportStore reportStore;
    private final MetadataQueryService metadataQueryService;

    public MetadataApiController(
            MetadataMaintenanceService metadataMaintenanceService,
            MetadataInspectionTaskService metadataInspectionTaskService,
            MetadataInspectionReportStore reportStore,
            MetadataQueryService metadataQueryService
    ) {
        this.metadataMaintenanceService = metadataMaintenanceService;
        this.metadataInspectionTaskService = metadataInspectionTaskService;
        this.reportStore = reportStore;
        this.metadataQueryService = metadataQueryService;
    }

    @GetMapping
    public MetadataPagePayload metadata() throws IOException {
        return metadataQueryService.load();
    }

    @PostMapping("/scan")
    public ResponseEntity<MetadataInspectionActionResponse> scan(
            @RequestParam(name = "areas", required = false) List<String> areas,
            HttpServletRequest request
    ) {
        QueuedInspection queued = metadataInspectionTaskService.queueInspection(areas, request);
        FlashNotification notification = queued.replacedExistingTask()
                ? FlashNotification.info("Previous metadata inspection was canceled and a new inspection was queued.")
                : FlashNotification.info("Metadata inspection queued.");
        return ResponseEntity.ok(new MetadataInspectionActionResponse(
                true,
                notification,
                TaskPayload.from(queued.task()),
                queued.replacedExistingTask(),
                "/admin/metadata"
        ));
    }

    @PostMapping("/repair")
    public ResponseEntity<MetadataRepairActionResponse> repair(
            @RequestParam(name = "issues", required = false) List<String> issues,
            @RequestParam(name = "repairAll", defaultValue = "false") boolean repairAll
    ) {
        MetadataInspectionReport latestReport;
        try {
            latestReport = reportStore.latest();
        } catch (IOException ex) {
            FlashNotification notification = FlashNotification.error("The latest metadata report could not be loaded.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse(notification, List.of(), 0, 0, List.of(notification.message())));
        }

        List<String> repairTokens = repairAll ? repairableTokens(latestReport) : issues;
        MetadataRepairSummary summary = metadataMaintenanceService.repair(repairTokens);
        try {
            if (summary.anyRepaired()) {
                latestReport = latestReport.withoutIssueTokens(summary.repairedTokens());
                reportStore.save(latestReport);
            }
            return ResponseEntity.ok(new MetadataRepairActionResponse(
                    true,
                    repairNotification(summary),
                    summary.repairedTokens(),
                    summary.repaired(),
                    summary.failed(),
                    summary.messages(),
                    issueCount(latestReport),
                    repairableCount(latestReport)
            ));
        } catch (IOException ex) {
            FlashNotification notification = FlashNotification.error(
                    "Metadata repair completed, but the latest report could not be loaded."
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse(
                            notification,
                            summary.repairedTokens(),
                            summary.repaired(),
                            summary.failed(),
                            summary.messages()
                    ));
        }
    }

    private MetadataRepairActionResponse errorResponse(
            FlashNotification notification,
            List<String> repairedTokens,
            int repaired,
            int failed,
            List<String> messages
    ) {
        return new MetadataRepairActionResponse(
                false,
                notification,
                repairedTokens,
                repaired,
                failed,
                messages,
                0,
                0
        );
    }

    private List<String> repairableTokens(MetadataInspectionReport latestReport) {
        if (latestReport == null || !latestReport.present()) {
            return List.of();
        }
        return latestReport.scanReport().areaReports().stream()
                .flatMap(areaReport -> areaReport.issues().stream())
                .filter(MetadataIssue::repairable)
                .map(MetadataIssue::token)
                .toList();
    }

    private int issueCount(MetadataInspectionReport latestReport) {
        return latestReport == null || !latestReport.present() ? 0 : latestReport.scanReport().issueCount();
    }

    private int repairableCount(MetadataInspectionReport latestReport) {
        return latestReport == null || !latestReport.present() ? 0 : latestReport.scanReport().repairableCount();
    }

    private FlashNotification repairNotification(MetadataRepairSummary summary) {
        if (summary.failed() > 0) {
            return FlashNotification.warning(
                    "Metadata repair finished: " + summary.repaired() + " repaired, " + summary.failed() + " failed."
            );
        }
        if (summary.repaired() == 0) {
            return FlashNotification.info("No metadata issue was repaired.");
        }
        return FlashNotification.success("Metadata repair finished: " + summary.repaired() + " repaired.");
    }

    public record MetadataInspectionActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            boolean replacedExistingTask,
            String reportUrl
    ) {
    }

    public record MetadataRepairActionResponse(
            boolean ok,
            FlashNotification notification,
            List<String> repairedTokens,
            int repaired,
            int failed,
            List<String> messages,
            int issueCount,
            int repairableCount
    ) {
    }
}
