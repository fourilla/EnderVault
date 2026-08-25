package io.github.fourilla.endervault.web.metadata;

import io.github.fourilla.endervault.metadata.MetadataArea;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService;
import io.github.fourilla.endervault.metadata.MetadataMaintenanceService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.task.TaskPayload;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
}
