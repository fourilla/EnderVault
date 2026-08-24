package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileRequestMetadataInspectorTest {

    @Test
    void reportsExpiredAndBrokenRequestRecordsWithoutExposingRawToken() throws Exception {
        FileRequestService requestService = mock(FileRequestService.class);
        StorageService storageService = mock(StorageService.class);
        FileRequest expired = request("expired", "secret_expired_token", Instant.now().minusSeconds(1), true);
        FileRequest broken = request("broken", "secret_broken_token", Instant.now().plusSeconds(3600), true);
        when(requestService.list()).thenReturn(List.of(expired, broken));
        when(storageService.resolveVaultDirectory("missing")).thenThrow(new NoSuchFileException("missing"));

        FileRequestMetadataInspector inspector = new FileRequestMetadataInspector(
                requestService,
                storageService,
                new PublicLinkTokenService()
        );

        List<MetadataIssue> issues = inspector.inspect();

        assertThat(issues).extracting(MetadataIssue::action)
                .containsExactly(MetadataIssueAction.DELETE_FILE_REQUEST, MetadataIssueAction.REVOKE_FILE_REQUEST);
        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.detail()).doesNotContain("secret_expired_token", "secret_broken_token");
            assertThat(issue.detail()).contains("token ");
        });

        inspector.repair(MetadataIssueAction.REVOKE_FILE_REQUEST, broken.id());
        verify(requestService).revoke(broken.id());
    }

    private FileRequest request(String id, String token, Instant expiresAt, boolean enabled) {
        return new FileRequest(
                id,
                token,
                id,
                "",
                "missing",
                UploaderNamePolicy.OPTIONAL,
                1024,
                4096,
                3,
                List.of(),
                0,
                0,
                List.of(),
                Instant.now().minusSeconds(3600),
                expiresAt,
                enabled
        );
    }
}
