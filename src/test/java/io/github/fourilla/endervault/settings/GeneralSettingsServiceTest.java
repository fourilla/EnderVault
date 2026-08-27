package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
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
                nas.sticky-notes.background-color=#1B3033
                nas.sticky-notes.border-color=#4E8F8A
                nas.sticky-notes.text-color=#EAF6F4
                nas.recent.max-items=200
                nas.recent.record-directories=true
                nas.trash.retention-days=30
                nas.trash.cleanup-on-startup=true
                nas.trash.cleanup-interval-ms=3600000
                nas.file-tools.text-auto-load-max-bytes=1048576
                nas.file-tools.text-manual-load-max-bytes=20971520
                nas.file-tools.text-draft-retention-hours=168
                nas.file-tools.text-draft-cleanup-interval-ms=3600000
                nas.file-tools.text-draft-lease-seconds=300
                nas.file-tools.comic-max-pages=5000
                nas.file-tools.comic-page-max-bytes=104857600
                nas.file-tools.comic-info-max-bytes=65536
                nas.remote-download.enabled=true
                nas.remote-download.direct-enabled=true
                nas.remote-download.block-private-networks=true
                nas.remote-download.allowed-ports=80,443
                nas.remote-download.connect-timeout-seconds=10
                nas.remote-download.response-timeout-seconds=30
                nas.remote-download.max-redirects=5
                nas.remote-download.max-file-size-bytes=0
                nas.remote-download.history-limit=100
                nas.remote-download.worker-threads=2
                nas.remote-download.max-retries=2
                nas.remote-download.default-target-directory=
                nas.remote-download.skip-inspect-by-default=false
                """, StandardCharsets.UTF_8);

        NasProperties properties = new NasProperties();
        Files.createDirectories(tempDir.resolve("storage").resolve("incoming"));
        GeneralSettingsService service = service(properties, configFile);

        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("defaultView", "grid");
        parameters.set("defaultSort", "modified");
        parameters.set("defaultDirection", "desc");
        parameters.set("defaultPageSize", "120");
        parameters.set("stickyNoteBackgroundColor", "#152A2E");
        parameters.set("stickyNoteBorderColor", "#63C7BD");
        parameters.set("stickyNoteTextColor", "#F2FBFA");
        parameters.set("defaultConflictPolicy", "rename");
        parameters.set("recentMaxItems", "55");
        parameters.remove("recordDirectories");
        parameters.set("trashRetentionDays", "14");
        parameters.set("trashCleanupIntervalMinutes", "2");
        parameters.set("textAutoLoadMaxMib", "4");
        parameters.set("textManualLoadMaxMib", "8");
        parameters.set("textDraftRetentionHours", "72");
        parameters.set("textDraftCleanupIntervalMinutes", "3");
        parameters.set("textDraftLeaseSeconds", "180");
        parameters.set("comicMaxPages", "300");
        parameters.set("comicPageMaxMib", "0.25");
        parameters.set("comicInfoMaxKib", "4");
        parameters.set("remoteAllowedPorts", "80,443,8080");
        parameters.set("remoteConnectTimeoutSeconds", "12");
        parameters.set("remoteResponseTimeoutSeconds", "45");
        parameters.set("remoteMaxRedirects", "7");
        parameters.set("remoteMaxFileSizeGib", "1.5");
        parameters.set("remoteHistoryLimit", "25");
        parameters.set("remoteWorkerThreads", "3");
        parameters.set("remoteMaxRetries", "3");
        parameters.set("remoteDefaultTargetDirectory", "incoming");
        parameters.set("remoteSkipInspectByDefault", "on");

        GeneralSettingsService.GeneralSettingsUpdate update = service.updateFrom(parameters);
        assertThat(service.requiresRestart(update)).isTrue();
        service.save(update);

        String savedConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        assertThat(savedConfig)
                .contains("nas.browser.default-view=grid")
                .contains("nas.browser.default-sort=modified")
                .contains("nas.browser.default-direction=desc")
                .contains("nas.browser.default-page-size=120")
                .contains("nas.sticky-notes.background-color=#152A2E")
                .contains("nas.sticky-notes.border-color=#63C7BD")
                .contains("nas.sticky-notes.text-color=#F2FBFA")
                .contains("nas.storage.default-conflict-policy=rename")
                .contains("nas.recent.max-items=55")
                .contains("nas.recent.record-directories=false")
                .contains("nas.trash.retention-days=14")
                .contains("nas.trash.cleanup-interval-ms=120000")
                .contains("nas.file-tools.text-auto-load-max-bytes=4194304")
                .contains("nas.file-tools.text-manual-load-max-bytes=8388608")
                .contains("nas.file-tools.text-draft-retention-hours=72")
                .contains("nas.file-tools.text-draft-cleanup-interval-ms=180000")
                .contains("nas.file-tools.text-draft-lease-seconds=180")
                .contains("nas.file-tools.comic-max-pages=300")
                .contains("nas.remote-download.allowed-ports=80,443,8080")
                .contains("nas.remote-download.connect-timeout-seconds=12")
                .contains("nas.remote-download.max-file-size-bytes=1610612736")
                .contains("nas.remote-download.max-retries=3")
                .contains("nas.remote-download.worker-threads=3")
                .contains("nas.remote-download.default-target-directory=incoming")
                .contains("nas.remote-download.skip-inspect-by-default=true");

        assertThat(properties.getBrowser().getDefaultView()).isEqualTo("grid");
        assertThat(properties.getBrowser().getDefaultSort()).isEqualTo("modified");
        assertThat(properties.getBrowser().getDefaultDirection()).isEqualTo("desc");
        assertThat(properties.getBrowser().getDefaultPageSize()).isEqualTo(120);
        assertThat(properties.getStickyNotes().getBackgroundColor()).isEqualTo("#152A2E");
        assertThat(properties.getStickyNotes().getBorderColor()).isEqualTo("#63C7BD");
        assertThat(properties.getStickyNotes().getTextColor()).isEqualTo("#F2FBFA");
        assertThat(properties.getStorage().getDefaultConflictPolicy()).isEqualTo("rename");
        assertThat(properties.getRecent().getMaxItems()).isEqualTo(55);
        assertThat(properties.getRecent().isRecordDirectories()).isFalse();
        assertThat(properties.getTrash().getRetentionDays()).isEqualTo(14);
        assertThat(properties.getFileTools().getTextManualLoadMaxBytes()).isEqualTo(8388608);
        assertThat(properties.getFileTools().getTextDraftRetentionHours()).isEqualTo(72);
        assertThat(properties.getFileTools().getTextDraftCleanupIntervalMs()).isEqualTo(180000);
        assertThat(properties.getFileTools().getTextDraftLeaseSeconds()).isEqualTo(180);
        assertThat(properties.getRemoteDownload().getAllowedPorts()).isEqualTo(List.of(80, 443, 8080));
        assertThat(properties.getRemoteDownload().getConnectTimeoutSeconds()).isEqualTo(12);
        assertThat(properties.getRemoteDownload().getMaxFileSizeBytes()).isEqualTo(1610612736L);
        assertThat(properties.getRemoteDownload().getMaxRetries()).isEqualTo(3);
        assertThat(properties.getRemoteDownload().getDefaultTargetDirectory()).isEqualTo("incoming");
        assertThat(properties.getRemoteDownload().isSkipInspectByDefault()).isTrue();
    }

    @Test
    void rejectsManualTextLimitBelowAutoLoadLimit() throws Exception {
        GeneralSettingsService service = service(new NasProperties(), tempDir.resolve("missing.properties"));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("textAutoLoadMaxMib", "8");
        parameters.set("textManualLoadMaxMib", "4");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual-load limit");
    }

    @Test
    void rejectsUnsafeStickyNoteColorValues() throws Exception {
        GeneralSettingsService service = service(new NasProperties(), tempDir.resolve("missing.properties"));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("stickyNoteBackgroundColor", "red; background-image:url(example)");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#RRGGBB");
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("defaultView", "table");
        parameters.add("defaultSort", "name");
        parameters.add("defaultDirection", "asc");
        parameters.add("defaultPageSize", "200");
        parameters.add("stickyNoteBackgroundColor", "#1B3033");
        parameters.add("stickyNoteBorderColor", "#4E8F8A");
        parameters.add("stickyNoteTextColor", "#EAF6F4");
        parameters.add("defaultConflictPolicy", "cancel");
        parameters.add("recentMaxItems", "200");
        parameters.add("recordDirectories", "on");
        parameters.add("trashRetentionDays", "30");
        parameters.add("trashCleanupOnStartup", "on");
        parameters.add("trashCleanupIntervalMinutes", "60");
        parameters.add("textAutoLoadMaxMib", "1");
        parameters.add("textManualLoadMaxMib", "20");
        parameters.add("textDraftRetentionHours", "168");
        parameters.add("textDraftCleanupIntervalMinutes", "60");
        parameters.add("textDraftLeaseSeconds", "300");
        parameters.add("comicMaxPages", "5000");
        parameters.add("comicPageMaxMib", "100");
        parameters.add("comicInfoMaxKib", "64");
        parameters.add("remoteEnabled", "on");
        parameters.add("remoteDirectEnabled", "on");
        parameters.add("remoteBlockPrivateNetworks", "on");
        parameters.add("remoteAllowedPorts", "80,443");
        parameters.add("remoteConnectTimeoutSeconds", "10");
        parameters.add("remoteResponseTimeoutSeconds", "30");
        parameters.add("remoteMaxRedirects", "5");
        parameters.add("remoteMaxFileSizeGib", "0");
        parameters.add("remoteHistoryLimit", "100");
        parameters.add("remoteWorkerThreads", "2");
        parameters.add("remoteMaxRetries", "2");
        parameters.add("remoteDefaultTargetDirectory", "");
        return parameters;
    }

    private GeneralSettingsService service(NasProperties properties, Path configFile) throws Exception {
        properties.getStorage().setRoot(tempDir.resolve("storage"));
        StorageService storageService = new StorageService(properties);
        storageService.initialize();
        return new GeneralSettingsService(properties, new LocalPropertiesFile(configFile), storageService);
    }
}
