package io.github.fourilla.endervault.web.api.v1.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.Operation;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.search.SearchQueryException;
import java.time.Instant;
import java.io.IOException;
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
        assertThat(controller.list(null).decisions()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("upload");
            assertThat(row.mergeId()).isNull();
        });

        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.of("review"));
        when(merges.unresolved()).thenReturn(List.of(review("review", Operation.PENDING, "upload"),
                review("copy", Operation.COPY, "source"), review("move", Operation.MOVE, "other"),
                review("recovery", Operation.PENDING, "removed-pending")));
        var rows = controller.list(null).decisions();
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
        assertThat(controller.list(null).decisions()).singleElement().satisfies(row -> {
            assertThat(row.mergeId()).isEqualTo("old-review");
            assertThat(row.statusLabel()).isEqualTo("Preparing or recovering review");
        });
    }

    @Test
    void filteringAnUploadMustNotReintroduceItsLinkedReviewAsAnotherMatchingRow() throws Exception {
        var upload = new PendingFileDecision("upload", PendingFileDecisionSource.FILE_REQUEST,
                "staging", "target", "album", 10, Instant.EPOCH, null, null, "Alice", true);
        when(pending.list()).thenReturn(List.of(upload));
        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.of("review"));
        when(merges.unresolved()).thenReturn(List.of(review("review", Operation.PENDING, "upload")));

        assertThat(controller.list("name:photos").decisions()).isEmpty();
        assertThat(controller.list("source:directory_upload").decisions()).isEmpty();
        assertThat(controller.list("source:file_request submitter:alice type:directory").decisions())
                .singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo("upload");
                    assertThat(row.mergeId()).isEqualTo("review");
                    assertThat(row.submittedBy()).isEqualTo("Alice");
                });
        verify(pending, never()).releaseDirectoryMergeClaim(anyString(), anyString());
    }

    @Test
    void standaloneTransfersAndRecoveryRowsUseTheirRawOperation() throws Exception {
        when(pending.list()).thenReturn(List.of());
        when(merges.unresolved()).thenReturn(List.of(review("copy", Operation.COPY, "source"),
                review("move", Operation.MOVE, "other"), review("recovery", Operation.PENDING, "missing")));

        assertThat(controller.list("source:directory_copy type:directory destination:target created:1970-01-01T00:00:00Z")
                .decisions()).extracting(row -> row.id()).containsExactly("merge-copy");
        assertThat(controller.list("source:directory_move || source:directory_upload").decisions())
                .extracting(row -> row.id()).containsExactly("merge-move", "merge-recovery");
        assertThat(controller.list("submitter:Alice").decisions()).isEmpty();
        assertThat(controller.list(" ").decisions()).isEqualTo(controller.list(null).decisions());
    }

    @Test
    void invalidQueryFailsBeforeAnyPendingOrTransferRead() {
        assertThatThrownBy(() -> controller.list("name:photos || source:unknown"))
                .isInstanceOf(SearchQueryException.class);
        verifyNoInteractions(pending, merges);
    }

    @Test
    void statusFindsOrdinaryOwnedPreparingAndStandaloneRowsWithoutChangingIdentity() throws Exception {
        var waiting = new PendingFileDecision("waiting", PendingFileDecisionSource.ADMIN_UPLOAD,
                "one", "target", "one.txt", 1, Instant.EPOCH, null, null, null);
        var owned = new PendingFileDecision("owned", PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "two", "target", "album", 10, Instant.EPOCH, null, null, null, true);
        var preparing = new PendingFileDecision("preparing", PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "three", "target", "preparing", 10, Instant.EPOCH, null, null, null, true);
        when(pending.list()).thenReturn(List.of(waiting, owned, preparing));
        when(pending.directoryMergeOwner("owned")).thenReturn(Optional.of("owned-review"));
        when(pending.directoryMergeOwner("preparing")).thenReturn(Optional.of("not-yet-visible"));
        when(merges.unresolved()).thenReturn(List.of(review("owned-review", Operation.PENDING, "owned"),
                review("copy", Operation.COPY, "source")));

        assertThat(controller.list("status:awaiting_decision").decisions())
                .extracting(row -> row.id()).containsExactly("waiting");
        assertThat(controller.list("status:preparing_review").decisions())
                .extracting(row -> row.id()).containsExactly("preparing");
        assertThat(controller.list("status:awaiting_review").decisions())
                .extracting(row -> row.id()).containsExactly("owned", "merge-copy");
        assertThat(controller.list("source:directory_copy status:awaiting_review").decisions())
                .extracting(row -> row.id()).containsExactly("merge-copy");
        assertThat(controller.list("source:directory_upload status:awaiting_review").decisions())
                .singleElement().satisfies(row -> assertThat(row.mergeId()).isEqualTo("owned-review"));
    }

    @Test
    void statusOwnerLookupIsLazySharedWithPayloadAndPreservesIoFailures() throws Exception {
        var upload = new PendingFileDecision("upload", PendingFileDecisionSource.DIRECTORY_UPLOAD,
                "staging", "target", "photos", 10, Instant.EPOCH, null, null, null, true);
        when(pending.list()).thenReturn(List.of(upload));
        when(merges.unresolved()).thenReturn(List.of());
        when(pending.directoryMergeOwner("upload")).thenReturn(Optional.empty());

        assertThat(controller.list("name:missing status:awaiting_decision").decisions()).isEmpty();
        verify(pending, never()).directoryMergeOwner(anyString());
        assertThat(controller.list("status:awaiting_decision status:awaiting_decision").decisions()).hasSize(1);
        verify(pending, times(1)).directoryMergeOwner("upload");
        var failure = new IOException("Owner unavailable");
        when(pending.directoryMergeOwner("upload")).thenThrow(failure);
        assertThatThrownBy(() -> controller.list("status:awaiting_decision")).isSameAs(failure);
    }

    private DirectoryTransferQueryService.Summary review(String id, Operation operation, String source) {
        return new DirectoryTransferQueryService.Summary(id, operation, source, "target/photos", Instant.EPOCH,
                0, 3, 1, false, true, null, null, true, false);
    }
}
