package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.thumbnail.ThumbnailCacheFile;
import io.github.fourilla.endervault.thumbnail.ThumbnailCacheScan;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ThumbnailMetadataInspector implements MetadataInspector {

    private final ThumbnailService thumbnailService;
    private final TemporaryArtifactRetentionPolicy retentionPolicy;

    public ThumbnailMetadataInspector(
            ThumbnailService thumbnailService,
            TemporaryArtifactRetentionPolicy retentionPolicy
    ) {
        this.thumbnailService = thumbnailService;
        this.retentionPolicy = retentionPolicy;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.THUMBNAILS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        ThumbnailCacheScan scan = thumbnailService.scanCache(context);
        List<MetadataIssue> issues = new ArrayList<>();
        for (ThumbnailCacheFile file : scan.orphanFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            issues.add(new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.WARNING,
                    MetadataIssueAction.DELETE_THUMBNAIL_CACHE,
                    file.relativePath(),
                    "Thumbnail cache file has no current source",
                    file.relativePath() + " (" + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                    "Delete this orphan thumbnail cache file."
            ));
        }
        for (ThumbnailCacheFile file : scan.temporaryFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            boolean stale = retentionPolicy.isStale(file.modifiedAt(), now);
            issues.add(new MetadataIssue(
                    area(),
                    stale ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                    stale ? MetadataIssueAction.DELETE_THUMBNAIL_CACHE : MetadataIssueAction.NONE,
                    file.relativePath(),
                    stale
                            ? "Stale thumbnail temporary file remains"
                            : "Fresh thumbnail temporary file exists",
                    file.relativePath() + " (" + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                    stale
                            ? "Delete this disposable thumbnail temporary file."
                            : "Review only. It may belong to a recent thumbnail operation."
            ));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_THUMBNAIL_CACHE) {
            throw new IllegalArgumentException("Unsupported thumbnail repair action.");
        }
        thumbnailService.deleteCacheFile(subject);
        return "Deleted thumbnail cache file: " + subject;
    }
}
