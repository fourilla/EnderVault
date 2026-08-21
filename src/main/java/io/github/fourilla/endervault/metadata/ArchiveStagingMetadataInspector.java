package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.FileCommitBatchCoordinator;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.ArchiveStagingInfo;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ArchiveStagingMetadataInspector implements MetadataInspector {

    private final StorageService storageService;
    private final TemporaryArtifactRetentionPolicy retentionPolicy;
    private final FileCommitBatchCoordinator fileCommitBatchCoordinator;

    public ArchiveStagingMetadataInspector(
            StorageService storageService,
            TemporaryArtifactRetentionPolicy retentionPolicy,
            FileCommitBatchCoordinator fileCommitBatchCoordinator
    ) {
        this.storageService = storageService;
        this.retentionPolicy = retentionPolicy;
        this.fileCommitBatchCoordinator = fileCommitBatchCoordinator;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.ARCHIVE_STAGING;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        StorageProgressListener progress = context == null
                ? StorageProgressListener.NOOP
                : new StorageProgressListener() {
                    @Override
                    public void checkCanceled() {
                        context.checkCanceled();
                    }
                };
        List<MetadataIssue> issues = new ArrayList<>();
        Set<String> journalWorkspaces = fileCommitBatchCoordinator.activeArchiveStagingWorkspaces();
        for (ArchiveStagingInfo artifact : storageService.listArchiveStagingArtifacts(progress)) {
            if (context != null) {
                context.checkCanceled();
            }
            boolean stale = retentionPolicy.isStale(artifact.modifiedAt(), now);
            boolean journalActive = journalWorkspaces.contains(artifact.name());
            boolean active = artifact.active() || journalActive;
            boolean repairable = stale && !active;
            issues.add(new MetadataIssue(
                    area(),
                    repairable ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                    repairable ? MetadataIssueAction.DELETE_ARCHIVE_STAGING : MetadataIssueAction.NONE,
                    artifact.name(),
                    active
                            ? "Active archive staging workspace"
                            : stale
                                    ? "Stale archive staging workspace remains"
                                    : "Fresh archive staging workspace exists",
                    artifact.name() + " (" + artifact.operation() + ", " + artifact.sizeLabel()
                            + ", modified " + artifact.modifiedLabel() + ")",
                    active
                            ? "In use by " + (journalActive ? "File commit recovery" : artifact.activeOperation())
                                    + ". The inspector will not delete it."
                            : repairable
                                    ? "Review the workspace and delete it if the interrupted archive work is no longer needed."
                                    : "Review only. It may belong to a recently interrupted archive operation."
            ));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_ARCHIVE_STAGING) {
            throw new IllegalArgumentException("Unsupported archive staging repair action.");
        }
        if (fileCommitBatchCoordinator.referencesArchiveStagingWorkspace(subject)) {
            throw new StorageAccessException("Archive staging workspace is protected by a file commit journal.");
        }
        storageService.deleteArchiveStagingArtifact(subject);
        return "Deleted archive staging workspace: " + subject;
    }
}
