package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskOutcome;
import io.github.fourilla.endervault.task.TaskType;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class MetadataInspectionTaskService {

    private final TaskManagerService taskManagerService;
    private final MetadataMaintenanceService metadataMaintenanceService;
    private final MetadataInspectionReportStore reportStore;
    private final ClientIpResolver clientIpResolver;
    private final AtomicLong generation = new AtomicLong();

    public MetadataInspectionTaskService(
            TaskManagerService taskManagerService,
            MetadataMaintenanceService metadataMaintenanceService,
            MetadataInspectionReportStore reportStore,
            ClientIpResolver clientIpResolver
    ) {
        this.taskManagerService = taskManagerService;
        this.metadataMaintenanceService = metadataMaintenanceService;
        this.reportStore = reportStore;
        this.clientIpResolver = clientIpResolver;
    }

    public QueuedInspection queueInspection(List<String> selectedAreaNames, HttpServletRequest request) {
        int canceledTasks = taskManagerService.requestCancelActive(TaskType.METADATA_INSPECTION);
        long taskGeneration = generation.incrementAndGet();
        List<MetadataArea> selectedAreas = metadataMaintenanceService.selectedAreas(selectedAreaNames);
        List<String> areaNames = selectedAreas.stream().map(MetadataArea::name).toList();
        RequestSnapshot snapshot = RequestSnapshot.from(request, clientIpResolver);
        String title = "Inspect " + selectedAreas.size() + " metadata area(s)";
        AppTask task = taskManagerService.submit(
                TaskType.METADATA_INSPECTION,
                title,
                "/admin/metadata",
                snapshot.actor(),
                snapshot.ip(),
                context -> {
                    MetadataScanReport report = metadataMaintenanceService.scan(areaNames, context);
                    context.checkCanceled();
                    if (generation.get() != taskGeneration) {
                        throw new TaskCanceledException();
                    }
                    reportStore.save(MetadataInspectionReport.from(selectedAreas, report));
                    int issues = report.issueCount();
                    return TaskOutcome.complete("Metadata inspection complete: " + issues + " issue(s).");
                }
        );
        return new QueuedInspection(task, canceledTasks);
    }

    public AppTask activeInspection() {
        return taskManagerService.newestActiveTask(TaskType.METADATA_INSPECTION);
    }

    public record QueuedInspection(AppTask task, int canceledTasks) {
        public boolean replacedExistingTask() {
            return canceledTasks > 0;
        }
    }

    private record RequestSnapshot(String actor, String ip) {
        static RequestSnapshot from(HttpServletRequest request, ClientIpResolver clientIpResolver) {
            Principal principal = request == null ? null : request.getUserPrincipal();
            String actor = principal == null ? "admin" : principal.getName();
            String ip = request == null ? "-" : clientIpResolver.resolve(request);
            return new RequestSnapshot(actor, ip);
        }
    }
}
