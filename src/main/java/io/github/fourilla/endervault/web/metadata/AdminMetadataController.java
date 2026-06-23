package io.github.fourilla.endervault.web.metadata;

import io.github.fourilla.endervault.metadata.MetadataArea;
import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService.QueuedInspection;
import io.github.fourilla.endervault.metadata.MetadataIssue;
import io.github.fourilla.endervault.metadata.MetadataMaintenanceService;
import io.github.fourilla.endervault.metadata.MetadataRepairSummary;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminMetadataController {

    private final MetadataMaintenanceService metadataMaintenanceService;
    private final MetadataInspectionTaskService metadataInspectionTaskService;
    private final MetadataInspectionReportStore reportStore;

    public AdminMetadataController(
            MetadataMaintenanceService metadataMaintenanceService,
            MetadataInspectionTaskService metadataInspectionTaskService,
            MetadataInspectionReportStore reportStore
    ) {
        this.metadataMaintenanceService = metadataMaintenanceService;
        this.metadataInspectionTaskService = metadataInspectionTaskService;
        this.reportStore = reportStore;
    }

    @GetMapping("/admin/metadata")
    public String metadata(Model model) throws IOException {
        populateBaseModel(model, metadataMaintenanceService.availableAreas());
        model.addAttribute("latestReport", reportStore.latest());
        model.addAttribute("activeInspectionTask", taskPayload(metadataInspectionTaskService.activeInspection()));
        return "metadata";
    }

    @PostMapping("/admin/metadata/scan")
    public Object scan(
            @RequestParam(name = "areas", required = false) List<String> areas,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        QueuedInspection queued = metadataInspectionTaskService.queueInspection(areas, request);
        FlashNotification notification = queued.replacedExistingTask()
                ? FlashNotification.info("Previous metadata inspection was canceled and a new inspection was queued.")
                : FlashNotification.info("Metadata inspection queued.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToMetadata(),
                new MetadataInspectionActionResponse(
                        true,
                        notification,
                        TaskPayload.from(queued.task()),
                        queued.replacedExistingTask(),
                        "/admin/metadata"
                )
        );
    }

    @PostMapping("/admin/metadata/repair")
    public Object repair(
            @RequestParam(name = "issues", required = false) List<String> issues,
            @RequestParam(name = "repairAll", defaultValue = "false") boolean repairAll,
            HttpServletRequest request,
            Model model
    ) {
        MetadataInspectionReport latestReport;
        try {
            latestReport = reportStore.latest();
        } catch (IOException ex) {
            FlashNotification notification = FlashNotification.error("The latest metadata report could not be loaded.");
            if (ActionResponseSupport.wantsJson(request)) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new MetadataRepairActionResponse(
                                false,
                                notification,
                                List.of(),
                                0,
                                0,
                                List.of(notification.message()),
                                0,
                                0
                        ));
            }
            populateBaseModel(model, metadataMaintenanceService.availableAreas());
            model.addAttribute("latestReport", MetadataInspectionReport.empty());
            model.addAttribute("notifications", List.of(notification));
            return "metadata";
        }

        List<String> repairTokens = repairAll ? repairableTokens(latestReport) : issues;
        MetadataRepairSummary summary = metadataMaintenanceService.repair(repairTokens);
        try {
            if (summary.anyRepaired()) {
                latestReport = latestReport.withoutIssueTokens(summary.repairedTokens());
                reportStore.save(latestReport);
            }
            FlashNotification notification = repairNotification(summary);
            if (ActionResponseSupport.wantsJson(request)) {
                return ResponseEntity.ok(new MetadataRepairActionResponse(
                        true,
                        notification,
                        summary.repairedTokens(),
                        summary.repaired(),
                        summary.failed(),
                        summary.messages(),
                        issueCount(latestReport),
                        repairableCount(latestReport)
                ));
            }
            populateBaseModel(model, latestReport.selectedAreas());
            model.addAttribute("latestReport", latestReport);
            model.addAttribute("repairSummary", summary);
            model.addAttribute("notifications", List.of(notification));
        } catch (IOException ex) {
            FlashNotification notification = FlashNotification.error("Metadata repair completed, but the latest report could not be loaded.");
            if (ActionResponseSupport.wantsJson(request)) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new MetadataRepairActionResponse(
                                false,
                                notification,
                                summary.repairedTokens(),
                                summary.repaired(),
                                summary.failed(),
                                summary.messages(),
                                0,
                                0
                        ));
            }
            populateBaseModel(model, metadataMaintenanceService.availableAreas());
            model.addAttribute("repairSummary", summary);
            model.addAttribute("notifications", List.of(notification));
        }
        return "metadata";
    }

    private void populateBaseModel(Model model, List<MetadataArea> selectedAreas) {
        List<MetadataArea> availableAreas = metadataMaintenanceService.availableAreas();
        model.addAttribute("areas", availableAreas);
        model.addAttribute("selectedAreaNames", areaNames(selectedAreas.isEmpty() ? availableAreas : selectedAreas));
    }

    private List<String> areaNames(List<MetadataArea> areas) {
        return areas.stream().map(MetadataArea::name).toList();
    }

    private TaskPayload taskPayload(AppTask task) {
        return task == null ? null : TaskPayload.from(task);
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

    private String redirectToMetadata() {
        return "redirect:/admin/metadata";
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

    private record MetadataInspectionActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            boolean replacedExistingTask,
            String reportUrl
    ) {
    }

    private record MetadataRepairActionResponse(
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
