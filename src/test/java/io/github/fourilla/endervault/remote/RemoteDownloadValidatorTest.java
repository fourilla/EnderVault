package io.github.fourilla.endervault.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteDownloadValidatorTest {

    private NasProperties properties;
    private RemoteDownloadValidator validator;

    @BeforeEach
    void setUp() {
        properties = new NasProperties();
        validator = new RemoteDownloadValidator(properties);
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com/file.mp4"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("HTTP and HTTPS");
    }

    @Test
    void rejectsLocalhostWhenPrivateNetworksAreBlocked() {
        assertThatThrownBy(() -> validator.validate("http://localhost/file.mp4"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("blocked network");
    }

    @Test
    void rejectsPrivateNetworkAddresses() {
        assertThatThrownBy(() -> validator.validate("http://192.168.0.1/file.mp4"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("blocked network");
    }

    @Test
    void rejectsCloudMetadataLinkLocalAddresses() {
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("blocked network");
    }

    @Test
    void rejectsRedirectsToBlockedNetworks() {
        assertThatThrownBy(() -> validator.validateRedirect(
                URI.create("https://example.com/file.mp4"),
                "http://127.0.0.1/internal"
        ))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("blocked network");
    }

    @Test
    void rejectsPortsOutsideAllowList() {
        assertThatThrownBy(() -> validator.validate("https://example.com:8443/file.mp4"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("port is not allowed");
    }

    @Test
    void canAllowLoopbackForExplicitDevelopmentConfiguration() {
        properties.getRemoteDownload().setBlockPrivateNetworks(false);
        properties.getRemoteDownload().setAllowedPorts(List.of(8080));

        assertThat(validator.validate("http://127.0.0.1:8080/file.mp4"))
                .hasToString("http://127.0.0.1:8080/file.mp4");
    }
}
