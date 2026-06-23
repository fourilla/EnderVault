package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class GeneralSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesGeneralSettingsToLocalConfigAndRuntimeProperties() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, """
                nas.setup.accepted=true
                nas.storage.default-conflict-policy=cancel
                nas.browser.default-view=table
                nas.browser.default-sort=name
                nas.browser.default-direction=asc
                nas.browser.default-page-size=200
                nas.recent.max-items=200
                nas.recent.record-directories=true
                nas.trash.retention-days=30
                nas.trash.cleanup-on-startup=true
                nas.trash.cleanup-interval-ms=3600000
                nas.file-tools.text-auto-load-max-bytes=1048576
                nas.file-tools.text-manual-load-max-bytes=20971520
                nas.file-tools.comic-max-pages=5000
                nas.file-tools.comic-page-max-bytes=104857600
                nas.file-tools.comic-info-max-bytes=65536
                nas.remote-download.enabled=true
                nas.remote-download.direct-enabled=true
                nas.remote-download.extractor-enabled=false
                nas.remote-download.block-private-networks=true
                nas.remote-download.allowed-ports=80,443
                nas.remote-download.response-timeout-seconds=30
                nas.remote-download.max-redirects=5
                nas.remote-download.max-file-size-bytes=0
                nas.remote-download.history-limit=100
                """, StandardCharsets.UTF_8);

        NasProperties properties = new NasProperties();
        GeneralSettingsService service = new GeneralSettingsService(properties, new LocalPropertiesFile(configFile));

        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("defaultView", "grid");
        parameters.set("defaultSort", "modified");
        parameters.set("defaultDirection", "desc");
        parameters.set("defaultPageSize", "120");
        parameters.set("defaultConflictPolicy", "rename");
        parameters.set("recentMaxItems", "55");
        parameters.remove("recordDirectories");
        parameters.set("trashRetentionDays", "14");
        parameters.set("trashCleanupIntervalMs", "120000");
        parameters.set("textAutoLoadMaxBytes", "4096");
        parameters.set("textManualLoadMaxBytes", "8192");
        parameters.set("comicMaxPages", "300");
        parameters.set("comicPageMaxBytes", "204800");
        parameters.set("comicInfoMaxBytes", "4096");
        parameters.remove("remoteExtractorEnabled");
        parameters.set("remoteAllowedPorts", "80,443,8080");
        parameters.set("remoteResponseTimeoutSeconds", "45");
        parameters.set("remoteMaxRedirects", "7");
        parameters.set("remoteMaxFileSizeBytes", "123456");
        parameters.set("remoteHistoryLimit", "25");

        service.save(service.updateFrom(parameters));

        String savedConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        assertThat(savedConfig)
                .contains("nas.browser.default-view=grid")
                .contains("nas.browser.default-sort=modified")
                .contains("nas.browser.default-direction=desc")
                .contains("nas.browser.default-page-size=120")
                .contains("nas.storage.default-conflict-policy=rename")
                .contains("nas.recent.max-items=55")
                .contains("nas.recent.record-directories=false")
                .contains("nas.trash.retention-days=14")
                .contains("nas.trash.cleanup-interval-ms=120000")
                .contains("nas.file-tools.text-auto-load-max-bytes=4096")
                .contains("nas.file-tools.text-manual-load-max-bytes=8192")
                .contains("nas.file-tools.comic-max-pages=300")
                .contains("nas.remote-download.extractor-enabled=false")
                .contains("nas.remote-download.allowed-ports=80,443,8080")
                .contains("nas.remote-download.max-file-size-bytes=123456");

        assertThat(properties.getBrowser().getDefaultView()).isEqualTo("grid");
        assertThat(properties.getBrowser().getDefaultSort()).isEqualTo("modified");
        assertThat(properties.getBrowser().getDefaultDirection()).isEqualTo("desc");
        assertThat(properties.getBrowser().getDefaultPageSize()).isEqualTo(120);
        assertThat(properties.getStorage().getDefaultConflictPolicy()).isEqualTo("rename");
        assertThat(properties.getRecent().getMaxItems()).isEqualTo(55);
        assertThat(properties.getRecent().isRecordDirectories()).isFalse();
        assertThat(properties.getTrash().getRetentionDays()).isEqualTo(14);
        assertThat(properties.getFileTools().getTextManualLoadMaxBytes()).isEqualTo(8192);
        assertThat(properties.getRemoteDownload().getAllowedPorts()).isEqualTo(List.of(80, 443, 8080));
        assertThat(properties.getRemoteDownload().getMaxFileSizeBytes()).isEqualTo(123456);
    }

    @Test
    void rejectsManualTextLimitBelowAutoLoadLimit() {
        GeneralSettingsService service = new GeneralSettingsService(new NasProperties(), new LocalPropertiesFile(tempDir.resolve("missing.properties")));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("textAutoLoadMaxBytes", "8192");
        parameters.set("textManualLoadMaxBytes", "4096");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual-load limit");
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("defaultView", "table");
        parameters.add("defaultSort", "name");
        parameters.add("defaultDirection", "asc");
        parameters.add("defaultPageSize", "200");
        parameters.add("defaultConflictPolicy", "cancel");
        parameters.add("recentMaxItems", "200");
        parameters.add("recordDirectories", "on");
        parameters.add("trashRetentionDays", "30");
        parameters.add("trashCleanupOnStartup", "on");
        parameters.add("trashCleanupIntervalMs", "3600000");
        parameters.add("textAutoLoadMaxBytes", "1048576");
        parameters.add("textManualLoadMaxBytes", "20971520");
        parameters.add("comicMaxPages", "5000");
        parameters.add("comicPageMaxBytes", "104857600");
        parameters.add("comicInfoMaxBytes", "65536");
        parameters.add("remoteEnabled", "on");
        parameters.add("remoteDirectEnabled", "on");
        parameters.add("remoteExtractorEnabled", "on");
        parameters.add("remoteBlockPrivateNetworks", "on");
        parameters.add("remoteAllowedPorts", "80,443");
        parameters.add("remoteResponseTimeoutSeconds", "30");
        parameters.add("remoteMaxRedirects", "5");
        parameters.add("remoteMaxFileSizeBytes", "0");
        parameters.add("remoteHistoryLimit", "100");
        return parameters;
    }
}
