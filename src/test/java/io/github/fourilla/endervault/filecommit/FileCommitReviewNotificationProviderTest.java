package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.notificationcenter.ActionRequiredItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileCommitReviewNotificationProviderTest {

    @Test
    void linksRecoveryItemsToTheJournalInspectorArea() throws Exception {
        FileCommitReviewService reviewService = mock(FileCommitReviewService.class);
        Instant updatedAt = Instant.now();
        when(reviewService.list()).thenReturn(List.of(new FileCommitReviewItem(
                "4b3e33e7-f279-46aa-a74f-c6da95d7acdd",
                FileCommitReviewKind.NEEDS_REVIEW,
                FileCommitOwnerType.REMOTE_DOWNLOAD,
                FileCommitOperationType.SINGLE_FILE,
                updatedAt.minusSeconds(10),
                updatedAt,
                "Target changed."
        )));
        FileCommitReviewNotificationProvider provider = new FileCommitReviewNotificationProvider(reviewService);

        List<ActionRequiredItem> items = provider.items();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("FILE_COMMIT_REVIEW");
            assertThat(item.title()).isEqualTo("File commit requires review");
            assertThat(item.createdAt()).isEqualTo(updatedAt);
            assertThat(item.href()).isEqualTo(provider.reviewAllHref());
        });
        assertThat(provider.reviewAllHref())
                .isEqualTo("/admin/metadata#metadata-scan-area-FILE_COMMIT_JOURNALS");
    }
}
