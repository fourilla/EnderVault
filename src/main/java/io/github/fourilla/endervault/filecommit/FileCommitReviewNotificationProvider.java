package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.notificationcenter.ActionRequiredItem;
import io.github.fourilla.endervault.notificationcenter.ActionRequiredProvider;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FileCommitReviewNotificationProvider implements ActionRequiredProvider {

    private static final String REVIEW_HREF =
            "/admin/metadata#metadata-scan-area-FILE_COMMIT_JOURNALS";

    private final FileCommitReviewService reviewService;

    public FileCommitReviewNotificationProvider(FileCommitReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public List<ActionRequiredItem> items() throws IOException {
        return reviewService.list().stream()
                .map(this::toActionRequiredItem)
                .toList();
    }

    @Override
    public String reviewAllHref() {
        return REVIEW_HREF;
    }

    private ActionRequiredItem toActionRequiredItem(FileCommitReviewItem item) {
        String title = switch (item.kind()) {
            case UNREADABLE -> "Unreadable file commit journal";
            case NEEDS_REVIEW -> "File commit requires review";
            case ABORTED -> "Aborted file commit requires review";
            case RECOVERY_DEFERRED -> "File commit recovery was deferred";
        };
        return new ActionRequiredItem(
                "file-commit-" + item.operationId(),
                "FILE_COMMIT_REVIEW",
                title,
                notificationDetail(item),
                item.updatedAt(),
                REVIEW_HREF
        );
    }

    private String notificationDetail(FileCommitReviewItem item) {
        if (item.kind() == FileCommitReviewKind.UNREADABLE) {
            return "A durable commit journal could not be read safely.";
        }
        if (item.ownerType() == null) {
            return "A recovery attempt could not be completed.";
        }
        return item.ownerType().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                + " is in " + item.kind().name().replace('_', ' ').toLowerCase(Locale.ROOT) + ".";
    }
}
