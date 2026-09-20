package io.github.fourilla.endervault.web.api.v1.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.Operation;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService;
import io.github.fourilla.endervault.pending.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PendingDecisionUnifiedListTest {
    private final PendingFileDecisionService pending = mock(PendingFileDecisionService.class);
    private final DirectoryTransferQueryService merges = mock(DirectoryTransferQueryService.class);
    private final PendingFileDecisionApiController controller = new PendingFileDecisionApiController(
            pending, mock(ActivityLogService.class), merges);

    @Test
    void uploadKeepsOneStableRowWhileTransfersAndOwnerCompletionRemainDiscoverable() throws Exception {
        var upload = new PendingFileDecision("upload", PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "staging", "target", "photos", 10, Instant.EPOCH, null, null, null, true);
        when(pending.list()).thenReturn(List.of(upload));
        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.empty());
        when(merges.unresolved()).thenReturn(List.of());
        assertThat(controller.list().decisions()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("upload");
            assertThat(row.mergeId()).isNull();
        });

        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.of("review"));
        when(merges.unresolved()).thenReturn(List.of(review("review", Operation.PENDING, "upload"),
                review("copy", Operation.COPY, "source"), review("move", Operation.MOVE, "other"),
                review("recovery", Operation.PENDING, "removed-pending")));
        var rows = controller.list().decisions();
        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().id()).isEqualTo("upload");
        assertThat(rows.getFirst().mergeId()).isEqualTo("review");
        assertThat(rows.getFirst().statusLabel()).isEqualTo("Upload merge awaiting review");
        assertThat(rows).extracting(row -> row.mergeId()).containsExactly("review", "copy", "move", "recovery");
        verify(pending, never()).releaseDirectoryMergeClaim(anyString(), anyString());
    }

    @Test
    void successorDoesNotDuplicateOrBypassThePendingOwnerDuringHandoff() throws Exception {
        var upload = new PendingFileDecision("upload", PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "staging", "target", "photos", 10, Instant.EPOCH, null, null, null, true);
        when(pending.list()).thenReturn(List.of(upload));
        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.of("old-review"));
        when(merges.unresolved()).thenReturn(List.of(review("successor", Operation.PENDING, "upload")));
        assertThat(controller.list().decisions()).singleElement().satisfies(row -> {
            assertThat(row.mergeId()).isEqualTo("old-review");
            assertThat(row.statusLabel()).isEqualTo("Preparing or recovering review");
        });
    }

    private DirectoryTransferQueryService.Summary review(String id, Operation operation, String source) {
        return new DirectoryTransferQueryService.Summary(id, operation, source, "target/photos", Instant.EPOCH,
                0, 3, 1, false, true, null, null, true);
    }
}
