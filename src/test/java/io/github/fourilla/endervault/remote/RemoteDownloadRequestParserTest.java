package io.github.fourilla.endervault.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteDownloadRequestParserTest {

    private RemoteDownloadRequestParser parser;

    @BeforeEach
    void setUp() {
        NasProperties properties = new NasProperties();
        properties.getRemoteDownload().setBlockPrivateNetworks(false);
        properties.getRemoteDownload().setAllowedPorts(List.of());
        parser = new RemoteDownloadRequestParser(new RemoteDownloadValidator(properties));
    }

    @Test
    void parsesCanonicalUrlAndCustomHeaders() {
        RemoteDownloadRequestSpec request = parser.parse(
                "https://example.com/file.bin?token=secret",
                "incoming",
                NetworkRoute.DIRECT,
                4,
                ConflictPolicy.RENAME,
                false,
                "Authorization: Bearer secret\nCookie: session=secret\nX-Client: EnderVault"
        );

        assertThat(request.sourceUri().toString()).contains("token=secret");
        assertThat(request.sourceLabel()).isEqualTo("https://example.com/file.bin?...");
        assertThat(request.requestedConnections()).isEqualTo(4);
        assertThat(request.conflictPolicy()).isEqualTo(ConflictPolicy.RENAME);
        assertThat(request.inspectionSkipped()).isFalse();
        assertThat(request.headers()).containsEntry("Authorization", "Bearer secret")
                .containsEntry("Cookie", "session=secret")
                .containsEntry("X-Client", "EnderVault");
    }

    @Test
    void rejectsHeadersThatCanChangeRequestSemanticsOrTransport() {
        assertThatThrownBy(() -> parser.parse(
                "https://example.com",
                "",
                NetworkRoute.DIRECT,
                1,
                ConflictPolicy.CANCEL,
                false,
                "Range: bytes=0-100"
        )).hasMessageContaining("managed by EnderVault");

        assertThatThrownBy(() -> parser.parse(
                "https://example.com/file.bin",
                "",
                NetworkRoute.DIRECT,
                1,
                ConflictPolicy.CANCEL,
                false,
                "X-HTTP-Method-Override: DELETE"
        )).hasMessageContaining("managed by EnderVault");
    }

    @Test
    void requiresCanonicalUrlEvenWhenCustomHeadersArePresent() {
        assertThatThrownBy(() -> parser.parse(
                "",
                "",
                NetworkRoute.DIRECT,
                1,
                ConflictPolicy.CANCEL,
                false,
                "Authorization: Bearer secret"
        )).hasMessageContaining("Remote URL is required");
    }

    @Test
    void enforcesConnectionLimitOnTheServer() {
        assertThatThrownBy(() -> parser.parse(
                "https://example.com/file",
                "",
                NetworkRoute.DIRECT,
                9,
                ConflictPolicy.CANCEL,
                false,
                ""
        )).hasMessageContaining("between 1 and 8");
    }

    @Test
    void requiresOneConnectionWhenInspectionIsSkipped() {
        assertThatThrownBy(() -> parser.parse(
                "https://example.com/file",
                "",
                NetworkRoute.DIRECT,
                2,
                ConflictPolicy.CANCEL,
                true,
                ""
        )).hasMessageContaining("exactly 1 connection");
    }
}
