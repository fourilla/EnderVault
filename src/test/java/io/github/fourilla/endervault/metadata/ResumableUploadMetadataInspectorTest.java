package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.upload.ResumableUploadCoordinator;
import io.github.fourilla.endervault.upload.ResumableUploadProtocolService;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadSource;
import io.github.fourilla.endervault.upload.ResumableUploadStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumableUploadMetadataInspectorTest {

    @Test
    void reportsActiveSessionAsReviewOnlyAndFailedSessionAsRepairable() throws Exception {
        ResumableUploadService uploadService = mock(ResumableUploadService.class);
        ResumableUploadProtocolService protocolService = mock(ResumableUploadProtocolService.class);
        ResumableUploadCoordinator coordinator = mock(ResumableUploadCoordinator.class);
        StorageService storageService = mock(StorageService.class);
        ResumableUploadSession active = session("11111111-1111-1111-1111-111111111111", ResumableUploadStatus.ADMITTED);
        ResumableUploadSession failed = session("22222222-2222-2222-2222-222222222222", ResumableUploadStatus.FAILED);
        ResumableUploadSession canceled = session(
                "33333333-3333-3333-3333-333333333333", ResumableUploadStatus.CANCELED
        );
        when(uploadService.list()).thenReturn(List.of(active, failed, canceled));
        when(uploadService.require(failed.id())).thenReturn(failed);
        when(protocolService.uploadDataExists(canceled.protocolUploadUri(), canceled.id())).thenReturn(true);

        ResumableUploadMetadataInspector inspector = new ResumableUploadMetadataInspector(
                uploadService,
                protocolService,
                coordinator,
                storageService
        );

        List<MetadataIssue> issues = inspector.inspect();

        assertThat(issues).hasSize(3);
        assertThat(issues.get(0).severity()).isEqualTo(MetadataIssueSeverity.INFO);
        assertThat(issues.get(0).action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issues.get(0).repairable()).isFalse();
        assertThat(issues.get(1).severity()).isEqualTo(MetadataIssueSeverity.WARNING);
        assertThat(issues.get(1).action()).isEqualTo(MetadataIssueAction.DELETE_RESUMABLE_UPLOAD);
        assertThat(issues.get(1).repairable()).isTrue();
        assertThat(issues.get(2).title()).contains("protocol data remains");
        assertThat(issues.get(2).repairable()).isTrue();

        inspector.repair(MetadataIssueAction.DELETE_RESUMABLE_UPLOAD, failed.id());
        verify(coordinator).deleteProtocolDataIfPresent(failed);
        verify(uploadService).remove(failed.id());
    }

    private ResumableUploadSession session(String id, ResumableUploadStatus status) {
        Instant now = Instant.now();
        return new ResumableUploadSession(
                id,
                ResumableUploadSource.ADMIN,
                "admin",
                "",
                "example.txt",
                "text/plain",
                7,
                null,
                "a".repeat(64),
                now.minusSeconds(60),
                now.plusSeconds(3600),
                status,
                status == ResumableUploadStatus.CANCELED
                        ? "/api/v1/uploads/" + id + "/44444444-4444-4444-4444-444444444444"
                        : null,
                null,
                null,
                null,
                status == ResumableUploadStatus.FAILED ? "failed" : null
        );
    }
}
