package io.github.fourilla.endervault.filecommit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FileCommitReviewService {

    private final FileCommitJournalStore journalStore;
    private final FileCommitRecoveryIncidentRegistry incidentRegistry;

    public FileCommitReviewService(
            FileCommitJournalStore journalStore,
            FileCommitRecoveryIncidentRegistry incidentRegistry
    ) {
        this.journalStore = journalStore;
        this.incidentRegistry = incidentRegistry;
    }

    public List<FileCommitReviewItem> list() throws IOException {
        List<FileCommitReviewItem> items = new ArrayList<>();
        Map<String, FileCommitRecoveryIncident> incidents = new HashMap<>();
        for (FileCommitRecoveryIncident incident : incidentRegistry.list()) {
            incidents.put(incident.operationId(), incident);
        }
        for (FileCommitJournalInspection inspection : journalStore.inspectJournals()) {
            FileCommitRecoveryIncident incident = incidents.remove(inspection.operationId());
            if (!inspection.readable()) {
                items.add(new FileCommitReviewItem(
                        inspection.operationId(),
                        FileCommitReviewKind.UNREADABLE,
                        null,
                        null,
                        inspection.observedAt(),
                        inspection.observedAt(),
                        inspection.failureType()
                ));
                continue;
            }

            FileCommitJournalEntry entry = inspection.entry();
            FileCommitReviewKind kind = reviewKind(entry.state().phase());
            if (kind == null && incident != null) {
                kind = FileCommitReviewKind.RECOVERY_DEFERRED;
            }
            if (kind == null) {
                continue;
            }
            items.add(new FileCommitReviewItem(
                    entry.manifest().operationId(),
                    kind,
                    entry.manifest().owner().type(),
                    entry.manifest().operationType(),
                    entry.manifest().createdAt(),
                    entry.state().updatedAt(),
                    kind == FileCommitReviewKind.RECOVERY_DEFERRED && incident != null
                            ? incident.failureType()
                            : entry.state().detail()
            ));
        }
        for (FileCommitRecoveryIncident incident : incidents.values()) {
            items.add(new FileCommitReviewItem(
                    incident.operationId(),
                    FileCommitReviewKind.RECOVERY_DEFERRED,
                    null,
                    null,
                    incident.observedAt(),
                    incident.observedAt(),
                    incident.failureType()
            ));
        }
        items.sort(Comparator.comparing(FileCommitReviewItem::updatedAt)
                .thenComparing(FileCommitReviewItem::operationId));
        return List.copyOf(items);
    }

    private FileCommitReviewKind reviewKind(FileCommitPhase phase) {
        return switch (phase) {
            case NEEDS_REVIEW -> FileCommitReviewKind.NEEDS_REVIEW;
            case ABORTED -> FileCommitReviewKind.ABORTED;
            default -> null;
        };
    }
}
