package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
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
    void savesMetadataSettingsWithoutFeatureSpecificNetworkRoute() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        BookmarkSettingsService service =
                new BookmarkSettingsService(properties, new LocalPropertiesFile(configFile));

        MultiValueMap<String, String> parameters = validParameters();
        parameters.remove("blockPrivateNetworks");
        BookmarkSettingsService.BookmarkSettingsUpdate update = service.updateFrom(parameters);
        assertThat(service.requiresRestart(update)).isFalse();
        service.save(update);

        assertThat(Files.readString(configFile, StandardCharsets.UTF_8))
                .contains("nas.bookmarks.block-private-networks=false")
                .doesNotContain("nas.bookmarks.metadata-network-route");
        assertThat(properties.getBookmarks().isBlockPrivateNetworks()).isFalse();

        parameters.set("faviconCacheDirectory", "bookmark-icons");
        assertThat(service.requiresRestart(service.updateFrom(parameters))).isTrue();
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("linkClickAction", "open");
        parameters.add("metadataFetchEnabled", "on");
        parameters.add("blockPrivateNetworks", "on");
        parameters.add("allowedPorts", "80,443");
        parameters.add("connectTimeoutSeconds", "5");
        parameters.add("responseTimeoutSeconds", "8");
        parameters.add("maxRedirects", "3");
        parameters.add("htmlMaxKib", "512");
        parameters.add("faviconMaxKib", "256");
        parameters.add("faviconCacheDirectory", "bookmark-favicons");
        return parameters;
    }
}
