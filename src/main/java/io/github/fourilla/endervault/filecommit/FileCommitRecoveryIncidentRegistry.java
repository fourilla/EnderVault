package io.github.fourilla.endervault.filecommit;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class FileCommitRecoveryIncidentRegistry {

    private final ConcurrentHashMap<String, FileCommitRecoveryIncident> incidents = new ConcurrentHashMap<>();

    public void record(String operationId, String failureType) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        String safeFailureType = failureType == null || failureType.isBlank()
                ? "DeferredRecovery"
                : failureType;
        incidents.put(operationId, new FileCommitRecoveryIncident(operationId, safeFailureType, Instant.now()));
    }

    public void clear(String operationId) {
        if (operationId != null) {
            incidents.remove(operationId);
        }
    }

    public List<FileCommitRecoveryIncident> list() {
        return incidents.values().stream()
                .sorted(Comparator.comparing(FileCommitRecoveryIncident::observedAt))
                .toList();
    }
}
