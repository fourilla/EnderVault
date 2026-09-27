package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.metadata.*;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompletedTaskRecordInspector implements MetadataInspector {
    private final DirectoryTransferCleanupService cleanup;

    public CompletedTaskRecordInspector(DirectoryTransferCleanupService cleanup) { this.cleanup = cleanup; }

    @Override public MetadataArea area() { return MetadataArea.COMPLETED_TASK_RECORDS; }

    @Override public List<MetadataIssue> inspect() throws IOException { return inspect(null); }

    @Override public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        if (context != null) context.checkCanceled();
        try {
            return cleanup.inspect(context).stream().map(candidate -> new MetadataIssue(area(), MetadataIssueSeverity.INFO,
                MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD, candidate.id(),
                candidate.operation() + " - " + candidate.state(),
                (candidate.destination().isEmpty() ? "Original destination unavailable" : "/" + candidate.destination())
                        + " | Task record: " + candidate.id(),
                "Delete the completed recovery record only. Files and Activity log are kept. Safety checks run again before deletion."
            )).toList();
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof io.github.fourilla.endervault.task.TaskCanceledException canceled) throw canceled;
            return List.of(new MetadataIssue(area(), MetadataIssueSeverity.WARNING, MetadataIssueAction.NONE,
                    "inspection-incomplete", "Completed task record inspection could not be completed",
                    "Some ownership or recovery records could not be verified. No cleanup was performed.",
                    "Inspect directory merges, pending decisions and file commit journals before retrying."));
        }
    }

    @Override public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD) throw new IllegalArgumentException("Unsupported completed record action.");
        if (!cleanup.cleanupOne(subject)) {
            throw new MetadataRepairSkippedException("Skipped task record " + subject + ": already removed or no longer safe to clean. Scan again to refresh the report.");
        }
        return "Deleted completed task record " + subject + ". Files and Activity log were kept.";
    }
}
