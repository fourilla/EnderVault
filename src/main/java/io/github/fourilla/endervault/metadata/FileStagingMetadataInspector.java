package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.FileStagingInfo;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FileStagingMetadataInspector implements MetadataInspector {

    private final StorageService storageService;
    private final TemporaryArtifactRetentionPolicy retentionPolicy;

    public FileStagingMetadataInspector(
            StorageService storageService,
            TemporaryArtifactRetentionPolicy retentionPolicy
    ) {
        this.storageService = storageService;
        this.retentionPolicy = retentionPolicy;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.FILE_STAGING;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        List<MetadataIssue> issues = new ArrayList<>();
        for (FileStagingInfo file : storageService.listFileStagingFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            boolean stale = retentionPolicy.isStale(file.modifiedAt(), now);
            boolean active = file.active();
            issues.add(new MetadataIssue(
                    area(),
                    stale && !active ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                    stale && !active ? MetadataIssueAction.DELETE_FILE_STAGING : MetadataIssueAction.NONE,
                    file.name(),
                    active
                            ? "Active file staging artifact"
                            : stale
                                    ? "Stale file staging artifact remains"
                                    : "Fresh file staging artifact exists",
                    file.name() + " (" + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                    active
                            ? "In use by " + file.activeOperation() + ". The inspector will not delete it."
                            : stale
                                    ? "Delete this disposable staging artifact."
                                    : "Review only. It may belong to a recent operation."
            ));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_FILE_STAGING) {
            throw new IllegalArgumentException("Unsupported file staging repair action.");
        }
        storageService.deleteFileStagingFile(subject);
        return "Deleted file staging artifact: " + subject;
    }
}
