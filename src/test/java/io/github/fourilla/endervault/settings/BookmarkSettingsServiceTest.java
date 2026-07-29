package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class BookmarkSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesMetadataNetworkRouteToLocalConfigAndRuntimeProperties() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        BookmarkSettingsService service =
                new BookmarkSettingsService(properties, new LocalPropertiesFile(configFile));

        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("metadataNetworkRoute", "vpn-required");
        service.save(service.updateFrom(parameters));

        assertThat(Files.readString(configFile, StandardCharsets.UTF_8))
                .contains("nas.bookmarks.metadata-network-route=vpn-required");
        assertThat(properties.getBookmarks().getMetadataNetworkRoute())
                .isEqualTo(NetworkRoute.VPN_REQUIRED);
    }

    @Test
    void rejectsUnknownMetadataNetworkRoute() {
        BookmarkSettingsService service = new BookmarkSettingsService(
                new NasProperties(),
                new LocalPropertiesFile(tempDir.resolve("missing.properties"))
        );
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("metadataNetworkRoute", "automatic");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Network route");
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("linkClickAction", "open");
        parameters.add("metadataFetchEnabled", "on");
        parameters.add("metadataNetworkRoute", "direct");
        parameters.add("blockPrivateNetworks", "on");
        parameters.add("allowedPorts", "80,443");
        parameters.add("connectTimeoutSeconds", "5");
        parameters.add("responseTimeoutSeconds", "8");
        parameters.add("maxRedirects", "3");
        parameters.add("htmlMaxBytes", "524288");
        parameters.add("faviconMaxBytes", "262144");
        parameters.add("faviconCacheDirectory", "bookmark-favicons");
        return parameters;
    }
}
