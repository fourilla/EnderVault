package io.github.fourilla.endervault.web.api.v1.metadata;

import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
import io.github.fourilla.endervault.metadata.MetadataInspectionTaskService;
import io.github.fourilla.endervault.metadata.MetadataMaintenanceService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.task.TaskPayload;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public class MetadataQueryService {

    private final MetadataMaintenanceService metadataMaintenanceService;
    private final MetadataInspectionTaskService metadataInspectionTaskService;
    private final MetadataInspectionReportStore reportStore;

    public MetadataQueryService(
            MetadataMaintenanceService metadataMaintenanceService,
            MetadataInspectionTaskService metadataInspectionTaskService,
            MetadataInspectionReportStore reportStore
    ) {
        this.metadataMaintenanceService = metadataMaintenanceService;
        this.metadataInspectionTaskService = metadataInspectionTaskService;
        this.reportStore = reportStore;
    }

    public MetadataPagePayload load() throws IOException {
        MetadataInspectionReport report = reportStore.latest();
        AppTask activeTask = metadataInspectionTaskService.activeInspection();
        return new MetadataPagePayload(
                metadataMaintenanceService.availableAreas().stream()
                        .map(MetadataPagePayload.MetadataAreaPayload::from)
                        .toList(),
                MetadataPagePayload.MetadataReportPayload.from(report),
                activeTask == null ? null : TaskPayload.from(activeTask)
        );
    }
}
