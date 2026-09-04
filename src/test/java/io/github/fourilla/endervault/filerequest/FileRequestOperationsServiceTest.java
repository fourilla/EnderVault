package io.github.fourilla.endervault.filerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.upload.ResumableUploadCoordinator;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileRequestOperationsServiceTest {

    @Test
    void expiredCleanupSkipsRequestsWithPendingFiles() throws Exception {
        FileRequestService requestService = mock(FileRequestService.class);
        ResumableUploadService uploadService = mock(ResumableUploadService.class);
        ResumableUploadCoordinator uploadCoordinator = mock(ResumableUploadCoordinator.class);
        PendingFileDecisionService pendingService = mock(PendingFileDecisionService.class);
        ActivityLogService activityService = mock(ActivityLogService.class);
        FileRequestOperationsService service = new FileRequestOperationsService(
                requestService, uploadService, uploadCoordinator, pendingService, activityService
        );
        FileRequest blocked = request("blocked");
        FileRequest deletable = request("deletable");
        PendingFileDecision pending = new PendingFileDecision(
                "pending", PendingFileDecisionSource.FILE_REQUEST, "staged.tmp", "", "file.txt",
                10, Instant.now(), null, "blocked:upload", null
        );
        when(requestService.list()).thenReturn(List.of(blocked, deletable));
        when(requestService.require("blocked")).thenReturn(blocked);
        when(requestService.require("deletable")).thenReturn(deletable);
        when(uploadService.list()).thenReturn(List.of());
        when(pendingService.list()).thenReturn(List.of(pending));
        when(activityService.recentByMetadata("requestId", "blocked", 50)).thenReturn(List.of());
        when(activityService.recentByMetadata("requestId", "deletable", 50)).thenReturn(List.of());

        FileRequestOperationsService.ExpiredDeletionResult result = service.deleteExpired(Instant.now());

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skippedRequestIds()).containsExactly("blocked");
        verify(requestService).delete("deletable");
        verify(requestService, never()).delete("blocked");
    }

    private FileRequest request(String id) {
        Instant now = Instant.now();
        return new FileRequest(
                id, "token-" + id, id, "", "", UploaderNamePolicy.OPTIONAL,
                1024, 4096, 3, List.of(), 0, 0, List.of(),
                now.minusSeconds(7200), now.minusSeconds(3600), true
        );
    }
}
