package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.filecommit.FileCommitOperationType;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filecommit.FileCommitReviewItem;
import io.github.fourilla.endervault.filecommit.FileCommitReviewKind;
import io.github.fourilla.endervault.filecommit.FileCommitReviewService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileCommitJournalMetadataInspectorTest {

    @Test
    void mapsRecoveryReviewItemsWithoutOfferingDestructiveRepair() throws Exception {
        FileCommitReviewService reviewService = mock(FileCommitReviewService.class);
        Instant now = Instant.now();
        when(reviewService.list()).thenReturn(List.of(
                new FileCommitReviewItem(
                        "4b3e33e7-f279-46aa-a74f-c6da95d7acdd",
                        FileCommitReviewKind.NEEDS_REVIEW,
                        FileCommitOwnerType.ARCHIVE_EXTRACT,
                        FileCommitOperationType.BATCH,
                        now.minusSeconds(10),
                        now,
                        "Archive target is incomplete."
                ),
                new FileCommitReviewItem(
                        "broken-journal",
                        FileCommitReviewKind.UNREADABLE,
                        null,
                        null,
                        now,
                        now,
                        "JsonParseException"
                )
        ));
        FileCommitJournalMetadataInspector inspector = new FileCommitJournalMetadataInspector(reviewService);

        List<MetadataIssue> issues = inspector.inspect();

        assertThat(issues).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.area()).isEqualTo(MetadataArea.FILE_COMMIT_JOURNALS);
            assertThat(issue.severity()).isEqualTo(MetadataIssueSeverity.DANGER);
            assertThat(issue.action()).isEqualTo(MetadataIssueAction.NONE);
            assertThat(issue.repairable()).isFalse();
        });
        assertThat(issues).extracting(MetadataIssue::title)
                .containsExactly("File commit requires review", "File commit journal is unreadable");
        assertThatThrownBy(() -> inspector.repair(MetadataIssueAction.NONE, "operation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual review");
    }
}
