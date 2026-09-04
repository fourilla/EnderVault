package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.filecommit.FileCommitReviewItem;
import io.github.fourilla.endervault.filecommit.FileCommitReviewKind;
import io.github.fourilla.endervault.filecommit.FileCommitReviewService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FileCommitJournalMetadataInspector implements MetadataInspector {

    private final FileCommitReviewService reviewService;

    public FileCommitJournalMetadataInspector(FileCommitReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.FILE_COMMIT_JOURNALS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        List<MetadataIssue> issues = new ArrayList<>();
        for (FileCommitReviewItem item : reviewService.list()) {
            if (context != null) {
                context.checkCanceled();
            }
            issues.add(toIssue(item));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) {
        throw new IllegalArgumentException("File commit journals require manual review.");
    }

    private MetadataIssue toIssue(FileCommitReviewItem item) {
        if (item.kind() == FileCommitReviewKind.UNREADABLE) {
            return new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.DANGER,
                    MetadataIssueAction.NONE,
                    item.operationId(),
                    "File commit journal is unreadable",
                    "Operation " + item.operationId() + " / observed " + item.updatedAt()
                            + " / " + safeDetail(item.detail()),
                    "Preserve the journal and staged data, then inspect the application log and files manually."
            );
        }

        boolean needsReview = item.kind() == FileCommitReviewKind.NEEDS_REVIEW;
        boolean deferred = item.kind() == FileCommitReviewKind.RECOVERY_DEFERRED;
        return new MetadataIssue(
                area(),
                needsReview ? MetadataIssueSeverity.DANGER : MetadataIssueSeverity.WARNING,
                MetadataIssueAction.NONE,
                item.operationId(),
                needsReview
                        ? "File commit requires review"
                        : deferred ? "File commit recovery was deferred" : "Aborted file commit journal remains",
                ownerLabel(item) + " / operation " + item.operationId() + " / updated " + item.updatedAt()
                        + " / " + safeDetail(item.detail()),
                needsReview
                        ? "Inspect both staged and destination data before deciding how to recover this operation."
                        : deferred
                                ? "Review the failure and retry recovery after the underlying filesystem issue is resolved."
                                : "Confirm that no pending owner still needs this operation before removing any data manually."
        );
    }

    private String ownerLabel(FileCommitReviewItem item) {
        if (item.ownerType() == null || item.operationType() == null) {
            return "unknown owner / unknown operation";
        }
        return item.ownerType().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                + " / " + item.operationType().name().replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String safeDetail(String detail) {
        return detail == null || detail.isBlank() ? "No additional detail" : detail;
    }
}
