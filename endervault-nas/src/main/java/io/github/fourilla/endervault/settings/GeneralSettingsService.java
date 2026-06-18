package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class GeneralSettingsService {

    private static final List<String> VIEWS = List.of("table", "grid");
    private static final List<String> SORTS = List.of("name", "size", "modified", "type");
    private static final List<String> DIRECTIONS = List.of("asc", "desc");

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;

    public GeneralSettingsService(NasProperties nasProperties, LocalPropertiesFile localPropertiesFile) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
    }

    public GeneralSettingsSnapshot currentSettings() {
        NasProperties.Browser browser = nasProperties.getBrowser();
        NasProperties.Recent recent = nasProperties.getRecent();
        NasProperties.Trash trash = nasProperties.getTrash();
        NasProperties.FileTools fileTools = nasProperties.getFileTools();
        NasProperties.RemoteDownload remoteDownload = nasProperties.getRemoteDownload();

        return new GeneralSettingsSnapshot(
                new BrowserSettings(
                        browser.getDefaultView(),
                        browser.getDefaultSort(),
                        browser.getDefaultDirection(),
                        browser.getDefaultPageSize()
                ),
                new RecentSettings(
                        recent.getMaxItems(),
                        recent.isRecordDirectories()
                ),
                new TrashSettings(
                        trash.getRetentionDays(),
                        trash.isCleanupOnStartup(),
                        trash.getCleanupIntervalMs()
                ),
                new FileToolSettings(
                        fileTools.getTextAutoLoadMaxBytes(),
                        fileTools.getTextManualLoadMaxBytes(),
                        fileTools.getComicMaxPages(),
                        fileTools.getComicPageMaxBytes(),
                        fileTools.getComicInfoMaxBytes()
                ),
                new RemoteDownloadSettings(
                        remoteDownload.isEnabled(),
                        remoteDownload.isDirectEnabled(),
                        remoteDownload.isExtractorEnabled(),
                        remoteDownload.isBlockPrivateNetworks(),
                        join(remoteDownload.getAllowedPorts()),
                        remoteDownload.getResponseTimeoutSeconds(),
                        remoteDownload.getMaxRedirects(),
                        remoteDownload.getMaxFileSizeBytes(),
                        remoteDownload.getHistoryLimit()
                ),
                localPropertiesFile.configFile().toString()
        );
    }

    public GeneralSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        String defaultView = oneOf(clean(first(parameters, "defaultView")), VIEWS, "Default view");
        String defaultSort = oneOf(clean(first(parameters, "defaultSort")), SORTS, "Default sort");
        String defaultDirection = oneOf(clean(first(parameters, "defaultDirection")), DIRECTIONS, "Default direction");
        int defaultPageSize = intRange(first(parameters, "defaultPageSize"), 1, 1000, "Default page size");

        int recentMaxItems = intRange(first(parameters, "recentMaxItems"), 1, 1000, "Recent max items");
        boolean recordDirectories = parameters.containsKey("recordDirectories");

        int trashRetentionDays = intRange(first(parameters, "trashRetentionDays"), 1, 36500, "Trash retention days");
        boolean cleanupOnStartup = parameters.containsKey("trashCleanupOnStartup");
        long cleanupIntervalMs = longRange(first(parameters, "trashCleanupIntervalMs"), 60000L, Long.MAX_VALUE, "Trash cleanup interval");

        long textAutoLoadMaxBytes = longRange(first(parameters, "textAutoLoadMaxBytes"), 1024L, Long.MAX_VALUE, "Text auto-load limit");
        long textManualLoadMaxBytes = longRange(first(parameters, "textManualLoadMaxBytes"), 1024L, Long.MAX_VALUE, "Text manual-load limit");
        if (textManualLoadMaxBytes < textAutoLoadMaxBytes) {
            throw new IllegalArgumentException("Text manual-load limit must be greater than or equal to the auto-load limit.");
        }
        int comicMaxPages = intRange(first(parameters, "comicMaxPages"), 1, 50000, "Comic max pages");
        long comicPageMaxBytes = longRange(first(parameters, "comicPageMaxBytes"), 1024L, Long.MAX_VALUE, "Comic page max bytes");
        long comicInfoMaxBytes = longRange(first(parameters, "comicInfoMaxBytes"), 1024L, Long.MAX_VALUE, "Comic info max bytes");

        boolean remoteEnabled = parameters.containsKey("remoteEnabled");
        boolean remoteDirectEnabled = parameters.containsKey("remoteDirectEnabled");
        boolean remoteExtractorEnabled = parameters.containsKey("remoteExtractorEnabled");
        boolean remoteBlockPrivateNetworks = parameters.containsKey("remoteBlockPrivateNetworks");
        List<Integer> allowedPorts = allowedPorts(first(parameters, "remoteAllowedPorts"));
        int responseTimeoutSeconds = intRange(first(parameters, "remoteResponseTimeoutSeconds"), 1, 3600, "Remote response timeout");
        int maxRedirects = intRange(first(parameters, "remoteMaxRedirects"), 0, 50, "Remote max redirects");
        long maxFileSizeBytes = longRange(first(parameters, "remoteMaxFileSizeBytes"), 0L, Long.MAX_VALUE, "Remote max file size");
        int historyLimit = intRange(first(parameters, "remoteHistoryLimit"), 1, 1000, "Remote history limit");

        return new GeneralSettingsUpdate(
                new BrowserSettings(defaultView, defaultSort, defaultDirection, defaultPageSize),
                new RecentSettings(recentMaxItems, recordDirectories),
                new TrashSettings(trashRetentionDays, cleanupOnStartup, cleanupIntervalMs),
                new FileToolSettings(
                        textAutoLoadMaxBytes,
                        textManualLoadMaxBytes,
                        comicMaxPages,
                        comicPageMaxBytes,
                        comicInfoMaxBytes
                ),
                new RemoteDownloadSettings(
                        remoteEnabled,
                        remoteDirectEnabled,
                        remoteExtractorEnabled,
                        remoteBlockPrivateNetworks,
                        join(allowedPorts),
                        responseTimeoutSeconds,
                        maxRedirects,
                        maxFileSizeBytes,
                        historyLimit
                ),
                allowedPorts
        );
    }

    public void save(GeneralSettingsUpdate update) throws IOException {
        persist(update);
        applyToRuntime(update);
    }

    private void persist(GeneralSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        BrowserSettings browser = update.browser();
        updates.put("nas.browser.default-view", browser.defaultView());
        updates.put("nas.browser.default-sort", browser.defaultSort());
        updates.put("nas.browser.default-direction", browser.defaultDirection());
        updates.put("nas.browser.default-page-size", Integer.toString(browser.defaultPageSize()));

        RecentSettings recent = update.recent();
        updates.put("nas.recent.max-items", Integer.toString(recent.maxItems()));
        updates.put("nas.recent.record-directories", Boolean.toString(recent.recordDirectories()));

        TrashSettings trash = update.trash();
        updates.put("nas.trash.retention-days", Integer.toString(trash.retentionDays()));
        updates.put("nas.trash.cleanup-on-startup", Boolean.toString(trash.cleanupOnStartup()));
        updates.put("nas.trash.cleanup-interval-ms", Long.toString(trash.cleanupIntervalMs()));

        FileToolSettings fileTools = update.fileTools();
        updates.put("nas.file-tools.text-auto-load-max-bytes", Long.toString(fileTools.textAutoLoadMaxBytes()));
        updates.put("nas.file-tools.text-manual-load-max-bytes", Long.toString(fileTools.textManualLoadMaxBytes()));
        updates.put("nas.file-tools.comic-max-pages", Integer.toString(fileTools.comicMaxPages()));
        updates.put("nas.file-tools.comic-page-max-bytes", Long.toString(fileTools.comicPageMaxBytes()));
        updates.put("nas.file-tools.comic-info-max-bytes", Long.toString(fileTools.comicInfoMaxBytes()));

        RemoteDownloadSettings remoteDownload = update.remoteDownload();
        updates.put("nas.remote-download.enabled", Boolean.toString(remoteDownload.enabled()));
        updates.put("nas.remote-download.direct-enabled", Boolean.toString(remoteDownload.directEnabled()));
        updates.put("nas.remote-download.extractor-enabled", Boolean.toString(remoteDownload.extractorEnabled()));
        updates.put("nas.remote-download.block-private-networks", Boolean.toString(remoteDownload.blockPrivateNetworks()));
        updates.put("nas.remote-download.allowed-ports", remoteDownload.allowedPorts());
        updates.put("nas.remote-download.response-timeout-seconds", Integer.toString(remoteDownload.responseTimeoutSeconds()));
        updates.put("nas.remote-download.max-redirects", Integer.toString(remoteDownload.maxRedirects()));
        updates.put("nas.remote-download.max-file-size-bytes", Long.toString(remoteDownload.maxFileSizeBytes()));
        updates.put("nas.remote-download.history-limit", Integer.toString(remoteDownload.historyLimit()));

        localPropertiesFile.update(updates, "# General settings managed from EnderVault Settings.");
    }

    private void applyToRuntime(GeneralSettingsUpdate update) {
        NasProperties.Browser browser = nasProperties.getBrowser();
        browser.setDefaultView(update.browser().defaultView());
        browser.setDefaultSort(update.browser().defaultSort());
        browser.setDefaultDirection(update.browser().defaultDirection());
        browser.setDefaultPageSize(update.browser().defaultPageSize());

        NasProperties.Recent recent = nasProperties.getRecent();
        recent.setMaxItems(update.recent().maxItems());
        recent.setRecordDirectories(update.recent().recordDirectories());

        NasProperties.Trash trash = nasProperties.getTrash();
        trash.setRetentionDays(update.trash().retentionDays());
        trash.setCleanupOnStartup(update.trash().cleanupOnStartup());
        trash.setCleanupIntervalMs(update.trash().cleanupIntervalMs());

        NasProperties.FileTools fileTools = nasProperties.getFileTools();
        fileTools.setTextAutoLoadMaxBytes(update.fileTools().textAutoLoadMaxBytes());
        fileTools.setTextManualLoadMaxBytes(update.fileTools().textManualLoadMaxBytes());
        fileTools.setComicMaxPages(update.fileTools().comicMaxPages());
        fileTools.setComicPageMaxBytes(update.fileTools().comicPageMaxBytes());
        fileTools.setComicInfoMaxBytes(update.fileTools().comicInfoMaxBytes());

        NasProperties.RemoteDownload remoteDownload = nasProperties.getRemoteDownload();
        remoteDownload.setEnabled(update.remoteDownload().enabled());
        remoteDownload.setDirectEnabled(update.remoteDownload().directEnabled());
        remoteDownload.setExtractorEnabled(update.remoteDownload().extractorEnabled());
        remoteDownload.setBlockPrivateNetworks(update.remoteDownload().blockPrivateNetworks());
        remoteDownload.setAllowedPorts(update.allowedPorts());
        remoteDownload.setResponseTimeoutSeconds(update.remoteDownload().responseTimeoutSeconds());
        remoteDownload.setMaxRedirects(update.remoteDownload().maxRedirects());
        remoteDownload.setMaxFileSizeBytes(update.remoteDownload().maxFileSizeBytes());
        remoteDownload.setHistoryLimit(update.remoteDownload().historyLimit());
    }

    private static List<Integer> allowedPorts(String rawValue) {
        String value = clean(rawValue);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Remote allowed ports is required.");
        }
        List<Integer> ports = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(port -> !port.isBlank())
                .map(port -> intRange(port, 1, 65535, "Remote allowed port"))
                .distinct()
                .toList();
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("Remote allowed ports is required.");
        }
        return ports;
    }

    private static String oneOf(String value, List<String> allowed, String label) {
        return allowed.stream()
                .filter(option -> option.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(label + " is invalid."));
    }

    private static int intRange(String rawValue, int min, int max, String label) {
        long value = longRange(rawValue, min, max, label);
        return Math.toIntExact(value);
    }

    private static long longRange(String rawValue, long min, long max, String label) {
        try {
            long value = Long.parseLong(clean(rawValue));
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static String join(List<Integer> values) {
        return values == null ? "" : String.join(",", values.stream().map(String::valueOf).toList());
    }

    public record GeneralSettingsSnapshot(
            BrowserSettings browser,
            RecentSettings recent,
            TrashSettings trash,
            FileToolSettings fileTools,
            RemoteDownloadSettings remoteDownload,
            String configPath
    ) {
    }

    public record GeneralSettingsUpdate(
            BrowserSettings browser,
            RecentSettings recent,
            TrashSettings trash,
            FileToolSettings fileTools,
            RemoteDownloadSettings remoteDownload,
            List<Integer> allowedPorts
    ) {
    }

    public record BrowserSettings(String defaultView, String defaultSort, String defaultDirection, int defaultPageSize) {
    }

    public record RecentSettings(int maxItems, boolean recordDirectories) {
    }

    public record TrashSettings(int retentionDays, boolean cleanupOnStartup, long cleanupIntervalMs) {
    }

    public record FileToolSettings(
            long textAutoLoadMaxBytes,
            long textManualLoadMaxBytes,
            int comicMaxPages,
            long comicPageMaxBytes,
            long comicInfoMaxBytes
    ) {
    }

    public record RemoteDownloadSettings(
            boolean enabled,
            boolean directEnabled,
            boolean extractorEnabled,
            boolean blockPrivateNetworks,
            String allowedPorts,
            int responseTimeoutSeconds,
            int maxRedirects,
            long maxFileSizeBytes,
            int historyLimit
    ) {
    }
}
