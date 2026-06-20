package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.TemporaryFileInfo;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UploadTempMetadataInspector implements MetadataInspector {

    private final StorageService storageService;
    private final NasProperties.MetadataInspector metadataInspectorProperties;

    public UploadTempMetadataInspector(StorageService storageService, NasProperties nasProperties) {
        this.storageService = storageService;
        this.metadataInspectorProperties = nasProperties.getMetadataInspector();
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.UPLOAD_TEMP;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        Duration staleAfter = Duration.ofMinutes(Math.max(1, metadataInspectorProperties.getUploadTempStaleMinutes()));
        List<MetadataIssue> issues = new ArrayList<>();
        for (TemporaryFileInfo file : storageService.listUploadTemporaryFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            Duration age = Duration.between(file.modifiedAt(), now);
            boolean stale = age.compareTo(staleAfter) >= 0;
            issues.add(new MetadataIssue(
                    area(),
                    stale ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                    stale ? MetadataIssueAction.DELETE_UPLOAD_TEMP : MetadataIssueAction.NONE,
                    file.name(),
                    stale ? "Stale upload temporary file remains" : "Fresh upload temporary file exists",
                    file.name() + " (" + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                    stale
                            ? "Delete this leftover upload temporary file."
                            : "Review only. It may belong to an active upload or recent conflict flow."
            ));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_UPLOAD_TEMP) {
            throw new IllegalArgumentException("Unsupported upload temp repair action.");
        }
        storageService.deleteUploadTemporaryFile(subject);
        return "Deleted upload temporary file: " + subject;
    }
}
