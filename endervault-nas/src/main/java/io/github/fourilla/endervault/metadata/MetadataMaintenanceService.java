package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MetadataMaintenanceService {

    private final Map<MetadataArea, MetadataInspector> inspectors;

    public MetadataMaintenanceService(List<MetadataInspector> inspectors) {
        this.inspectors = new EnumMap<>(MetadataArea.class);
        for (MetadataInspector inspector : inspectors) {
            this.inspectors.put(inspector.area(), inspector);
        }
    }

    public List<MetadataArea> availableAreas() {
        return inspectors.keySet().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
    }

    public List<MetadataArea> selectedAreas(Collection<String> values) {
        return MetadataArea.selected(values).stream()
                .filter(inspectors::containsKey)
                .toList();
    }

    public MetadataScanReport scan(Collection<String> values) throws IOException {
        return scan(values, null);
    }

    public MetadataScanReport scan(Collection<String> values, TaskContext context) throws IOException {
        List<MetadataAreaReport> reports = new ArrayList<>();
        List<MetadataArea> selectedAreas = selectedAreas(values);
        if (context != null) {
            context.setTotalItems(selectedAreas.size());
        }
        for (MetadataArea area : selectedAreas) {
            if (context != null) {
                context.checkCanceled();
                context.message("Scanning " + area.label() + ".");
            }
            MetadataInspector inspector = inspectors.get(area);
            reports.add(new MetadataAreaReport(area, inspector.inspect(context)));
            if (context != null) {
                context.incrementProcessedItems();
            }
        }
        return new MetadataScanReport(Instant.now(), List.copyOf(reports));
    }

    public MetadataRepairSummary repair(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return new MetadataRepairSummary(0, 0, List.of("No metadata issue was selected."), List.of());
        }

        int repaired = 0;
        int failed = 0;
        List<String> messages = new ArrayList<>();
        List<String> repairedTokens = new ArrayList<>();
        for (String token : tokens) {
            try {
                MetadataIssue.RepairRequest request = MetadataIssue.repairRequest(token);
                MetadataInspector inspector = inspectors.get(request.area());
                if (inspector == null) {
                    throw new IllegalArgumentException("Inspector not available for " + request.area().label() + ".");
                }
                if (!request.action().repairable()) {
                    throw new IllegalArgumentException("Selected issue is not repairable.");
                }
                messages.add(inspector.repair(request.action(), request.subject()));
                repairedTokens.add(token);
                repaired++;
            } catch (Exception ex) {
                failed++;
                messages.add(ex.getMessage() == null ? "Metadata repair failed." : ex.getMessage());
            }
        }
        return new MetadataRepairSummary(repaired, failed, List.copyOf(messages), List.copyOf(repairedTokens));
    }
}
