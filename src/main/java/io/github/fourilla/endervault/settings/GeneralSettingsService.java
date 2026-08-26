package io.github.fourilla.endervault.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class GeneralSettingsService {

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;
    private static final long GIB = 1024L * MIB;
    private static final long MINUTE_MS = 60000L;
    private static final List<String> VIEWS = List.of("table", "grid");
    private static final List<String> SORTS = List.of("name", "size", "modified", "type");
    private static final List<String> DIRECTIONS = List.of("asc", "desc");
    private static final List<String> CONFLICT_POLICIES = ConflictPolicy.valuesForSettings();
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;
    private final StorageService storageService;

    public GeneralSettingsService(
            NasProperties nasProperties,
            LocalPropertiesFile localPropertiesFile,
            StorageService storageService
    ) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
        this.storageService = storageService;
    }

    public GeneralSettingsSnapshot currentSettings() {
        NasProperties.Browser browser = nasProperties.getBrowser();
        NasProperties.StickyNotes stickyNotes = nasProperties.getStickyNotes();
        NasProperties.Storage storage = nasProperties.getStorage();
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
                new StickyNoteSettings(
                        stickyNotes.getBackgroundColor(),
                        stickyNotes.getBorderColor(),
                        stickyNotes.getTextColor()
                ),
                new StorageSettings(storage.getDefaultConflictPolicy()),
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
                        fileTools.getTextDraftRetentionHours(),
                        fileTools.getTextDraftCleanupIntervalMs(),
                        fileTools.getTextDraftLeaseSeconds(),
                        fileTools.getComicMaxPages(),
                        fileTools.getComicPageMaxBytes(),
                        fileTools.getComicInfoMaxBytes()
                ),
                new RemoteDownloadSettings(
                        remoteDownload.isEnabled(),
                        remoteDownload.isDirectEnabled(),
                        remoteDownload.isBlockPrivateNetworks(),
                        join(remoteDownload.getAllowedPorts()),
                        remoteDownload.getResponseTimeoutSeconds(),
                        remoteDownload.getMaxRedirects(),
                        remoteDownload.getMaxFileSizeBytes(),
                        remoteDownload.getHistoryLimit(),
                        remoteDownload.getMaxRetries(),
                        remoteDownload.isSkipInspectByDefault(),
                        remoteDownload.getDefaultTargetDirectory()
                ),
                localPropertiesFile.configFile().toString()
        );
    }

    public GeneralSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        String defaultView = oneOf(clean(first(parameters, "defaultView")), VIEWS, "Default view");
        String defaultSort = oneOf(clean(first(parameters, "defaultSort")), SORTS, "Default sort");
        String defaultDirection = oneOf(clean(first(parameters, "defaultDirection")), DIRECTIONS, "Default direction");
        int defaultPageSize = intRange(first(parameters, "defaultPageSize"), 1, 1000, "Default page size");
        StickyNoteSettings stickyNotes = new StickyNoteSettings(
                color(first(parameters, "stickyNoteBackgroundColor"), "Sticky note background color"),
                color(first(parameters, "stickyNoteBorderColor"), "Sticky note border color"),
                color(first(parameters, "stickyNoteTextColor"), "Sticky note text color")
        );
        String defaultConflictPolicy = oneOf(
                clean(first(parameters, "defaultConflictPolicy")),
                CONFLICT_POLICIES,
                "Default conflict policy"
        );

        int recentMaxItems = intRange(first(parameters, "recentMaxItems"), 1, 1000, "Recent max items");
        boolean recordDirectories = parameters.containsKey("recordDirectories");

        int trashRetentionDays = intRange(first(parameters, "trashRetentionDays"), 1, 36500, "Trash retention days");
        boolean cleanupOnStartup = parameters.containsKey("trashCleanupOnStartup");
        long cleanupIntervalMs = scaledLong(
                first(parameters, "trashCleanupIntervalMinutes"),
                MINUTE_MS, 60000L, Long.MAX_VALUE, "Trash cleanup interval"
        );

        long textAutoLoadMaxBytes = scaledLong(
                first(parameters, "textAutoLoadMaxMib"), MIB, 1024L, Long.MAX_VALUE, "Text auto-load limit"
        );
        long textManualLoadMaxBytes = scaledLong(
                first(parameters, "textManualLoadMaxMib"), MIB, 1024L, Long.MAX_VALUE, "Text manual-load limit"
        );
        if (textManualLoadMaxBytes < textAutoLoadMaxBytes) {
            throw new IllegalArgumentException("Text manual-load limit must be greater than or equal to the auto-load limit.");
        }
        long textDraftRetentionHours = longRange(
                first(parameters, "textDraftRetentionHours"),
                1L,
                Long.MAX_VALUE,
                "Text draft retention"
        );
        long textDraftCleanupIntervalMs = scaledLong(
                first(parameters, "textDraftCleanupIntervalMinutes"),
                MINUTE_MS, 60000L, Long.MAX_VALUE, "Text draft cleanup interval"
        );
        long textDraftLeaseSeconds = longRange(
                first(parameters, "textDraftLeaseSeconds"),
                30L,
                Long.MAX_VALUE,
                "Text draft lease"
        );
        int comicMaxPages = intRange(first(parameters, "comicMaxPages"), 1, 50000, "Comic max pages");
        long comicPageMaxBytes = scaledLong(
                first(parameters, "comicPageMaxMib"), MIB, 1024L, Long.MAX_VALUE, "Comic page limit"
        );
        long comicInfoMaxBytes = scaledLong(
                first(parameters, "comicInfoMaxKib"), KIB, 1024L, Long.MAX_VALUE, "Comic info limit"
        );

        boolean remoteEnabled = parameters.containsKey("remoteEnabled");
        boolean remoteDirectEnabled = parameters.containsKey("remoteDirectEnabled");
        boolean remoteBlockPrivateNetworks = parameters.containsKey("remoteBlockPrivateNetworks");
        List<Integer> allowedPorts = allowedPorts(first(parameters, "remoteAllowedPorts"));
        int responseTimeoutSeconds = intRange(first(parameters, "remoteResponseTimeoutSeconds"), 1, 3600, "Remote response timeout");
        int maxRedirects = intRange(first(parameters, "remoteMaxRedirects"), 0, 50, "Remote max redirects");
        long maxFileSizeBytes = scaledLong(
                first(parameters, "remoteMaxFileSizeGib"), GIB, 0L, Long.MAX_VALUE, "Remote max file size"
        );
        int historyLimit = intRange(first(parameters, "remoteHistoryLimit"), 1, 1000, "Remote history limit");
        String rawMaxRetries = first(parameters, "remoteMaxRetries");
        int maxRetries = rawMaxRetries == null || rawMaxRetries.isBlank()
                ? nasProperties.getRemoteDownload().getMaxRetries()
                : intRange(rawMaxRetries, 0, 5, "Remote max retries");
        boolean skipInspectByDefault = parameters.containsKey("remoteSkipInspectByDefault");
        String defaultTargetDirectory = normalizeDefaultTargetDirectory(
                first(parameters, "remoteDefaultTargetDirectory")
        );

        return new GeneralSettingsUpdate(
                new BrowserSettings(defaultView, defaultSort, defaultDirection, defaultPageSize),
                stickyNotes,
                new StorageSettings(defaultConflictPolicy),
                new RecentSettings(recentMaxItems, recordDirectories),
                new TrashSettings(trashRetentionDays, cleanupOnStartup, cleanupIntervalMs),
                new FileToolSettings(
                        textAutoLoadMaxBytes,
                        textManualLoadMaxBytes,
                        textDraftRetentionHours,
                        textDraftCleanupIntervalMs,
                        textDraftLeaseSeconds,
                        comicMaxPages,
                        comicPageMaxBytes,
                        comicInfoMaxBytes
                ),
                new RemoteDownloadSettings(
                        remoteEnabled,
                        remoteDirectEnabled,
                        remoteBlockPrivateNetworks,
                        join(allowedPorts),
                        responseTimeoutSeconds,
                        maxRedirects,
                        maxFileSizeBytes,
                        historyLimit,
                        maxRetries,
                        skipInspectByDefault,
                        defaultTargetDirectory
                ),
                allowedPorts
        );
    }

    public void save(GeneralSettingsUpdate update) throws IOException {
        persist(update);
        applyToRuntime(update);
    }

    public boolean requiresRestart(GeneralSettingsUpdate update) {
        return nasProperties.getTrash().getCleanupIntervalMs() != update.trash().cleanupIntervalMs()
                || nasProperties.getRemoteDownload().getHistoryLimit() != update.remoteDownload().historyLimit();
    }

    private void persist(GeneralSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        BrowserSettings browser = update.browser();
        updates.put("nas.browser.default-view", browser.defaultView());
        updates.put("nas.browser.default-sort", browser.defaultSort());
        updates.put("nas.browser.default-direction", browser.defaultDirection());
        updates.put("nas.browser.default-page-size", Integer.toString(browser.defaultPageSize()));

        StickyNoteSettings stickyNotes = update.stickyNotes();
        updates.put("nas.sticky-notes.background-color", stickyNotes.backgroundColor());
        updates.put("nas.sticky-notes.border-color", stickyNotes.borderColor());
        updates.put("nas.sticky-notes.text-color", stickyNotes.textColor());

        StorageSettings storage = update.storage();
        updates.put("nas.storage.default-conflict-policy", storage.defaultConflictPolicy());

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
        updates.put("nas.file-tools.text-draft-retention-hours", Long.toString(fileTools.textDraftRetentionHours()));
        updates.put(
                "nas.file-tools.text-draft-cleanup-interval-ms",
                Long.toString(fileTools.textDraftCleanupIntervalMs())
        );
        updates.put("nas.file-tools.text-draft-lease-seconds", Long.toString(fileTools.textDraftLeaseSeconds()));
        updates.put("nas.file-tools.comic-max-pages", Integer.toString(fileTools.comicMaxPages()));
        updates.put("nas.file-tools.comic-page-max-bytes", Long.toString(fileTools.comicPageMaxBytes()));
        updates.put("nas.file-tools.comic-info-max-bytes", Long.toString(fileTools.comicInfoMaxBytes()));

        RemoteDownloadSettings remoteDownload = update.remoteDownload();
        updates.put("nas.remote-download.enabled", Boolean.toString(remoteDownload.enabled()));
        updates.put("nas.remote-download.direct-enabled", Boolean.toString(remoteDownload.directEnabled()));
        updates.put("nas.remote-download.block-private-networks", Boolean.toString(remoteDownload.blockPrivateNetworks()));
        updates.put("nas.remote-download.allowed-ports", remoteDownload.allowedPorts());
        updates.put("nas.remote-download.response-timeout-seconds", Integer.toString(remoteDownload.responseTimeoutSeconds()));
        updates.put("nas.remote-download.max-redirects", Integer.toString(remoteDownload.maxRedirects()));
        updates.put("nas.remote-download.max-file-size-bytes", Long.toString(remoteDownload.maxFileSizeBytes()));
        updates.put("nas.remote-download.history-limit", Integer.toString(remoteDownload.historyLimit()));
        updates.put("nas.remote-download.max-retries", Integer.toString(remoteDownload.maxRetries()));
        updates.put(
                "nas.remote-download.skip-inspect-by-default",
                Boolean.toString(remoteDownload.skipInspectByDefault())
        );
        updates.put("nas.remote-download.default-target-directory", remoteDownload.defaultTargetDirectory());

        localPropertiesFile.update(updates, "# General settings managed from EnderVault Settings.");
    }

    private void applyToRuntime(GeneralSettingsUpdate update) {
        NasProperties.Browser browser = nasProperties.getBrowser();
        browser.setDefaultView(update.browser().defaultView());
        browser.setDefaultSort(update.browser().defaultSort());
        browser.setDefaultDirection(update.browser().defaultDirection());
        browser.setDefaultPageSize(update.browser().defaultPageSize());

        NasProperties.StickyNotes stickyNotes = nasProperties.getStickyNotes();
        stickyNotes.setBackgroundColor(update.stickyNotes().backgroundColor());
        stickyNotes.setBorderColor(update.stickyNotes().borderColor());
        stickyNotes.setTextColor(update.stickyNotes().textColor());

        NasProperties.Storage storage = nasProperties.getStorage();
        storage.setDefaultConflictPolicy(update.storage().defaultConflictPolicy());

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
        fileTools.setTextDraftRetentionHours(update.fileTools().textDraftRetentionHours());
        fileTools.setTextDraftCleanupIntervalMs(update.fileTools().textDraftCleanupIntervalMs());
        fileTools.setTextDraftLeaseSeconds(update.fileTools().textDraftLeaseSeconds());
        fileTools.setComicMaxPages(update.fileTools().comicMaxPages());
        fileTools.setComicPageMaxBytes(update.fileTools().comicPageMaxBytes());
        fileTools.setComicInfoMaxBytes(update.fileTools().comicInfoMaxBytes());

        NasProperties.RemoteDownload remoteDownload = nasProperties.getRemoteDownload();
        remoteDownload.setEnabled(update.remoteDownload().enabled());
        remoteDownload.setDirectEnabled(update.remoteDownload().directEnabled());
        remoteDownload.setBlockPrivateNetworks(update.remoteDownload().blockPrivateNetworks());
        remoteDownload.setAllowedPorts(update.allowedPorts());
        remoteDownload.setResponseTimeoutSeconds(update.remoteDownload().responseTimeoutSeconds());
        remoteDownload.setMaxRedirects(update.remoteDownload().maxRedirects());
        remoteDownload.setMaxFileSizeBytes(update.remoteDownload().maxFileSizeBytes());
        remoteDownload.setHistoryLimit(update.remoteDownload().historyLimit());
        remoteDownload.setMaxRetries(update.remoteDownload().maxRetries());
        remoteDownload.setSkipInspectByDefault(update.remoteDownload().skipInspectByDefault());
        remoteDownload.setDefaultTargetDirectory(update.remoteDownload().defaultTargetDirectory());
    }

    private String normalizeDefaultTargetDirectory(String rawPath) {
        try {
            return storageService.normalizeVaultDirectory(clean(rawPath));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Remote default destination must be an existing vault directory.", ex);
        }
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

    private static long scaledLong(String rawValue, long scale, long min, long max, String label) {
        try {
            BigDecimal displayValue = new BigDecimal(clean(rawValue));
            long value = displayValue.multiply(BigDecimal.valueOf(scale)).longValueExact();
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " is outside the supported range.");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(label + " has too much precision or is too large.");
        }
    }

    private static String scaledDisplay(long value, long scale) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(scale))
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String color(String rawValue, String label) {
        String value = clean(rawValue);
        if (!HEX_COLOR.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must use the #RRGGBB format.");
        }
        return value.toUpperCase(Locale.ROOT);
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
            StickyNoteSettings stickyNotes,
            StorageSettings storage,
            RecentSettings recent,
            TrashSettings trash,
            FileToolSettings fileTools,
            RemoteDownloadSettings remoteDownload,
            String configPath
    ) {
    }

    public record GeneralSettingsUpdate(
            BrowserSettings browser,
            StickyNoteSettings stickyNotes,
            StorageSettings storage,
            RecentSettings recent,
            TrashSettings trash,
            FileToolSettings fileTools,
            RemoteDownloadSettings remoteDownload,
            List<Integer> allowedPorts
    ) {
    }

    public record BrowserSettings(String defaultView, String defaultSort, String defaultDirection, int defaultPageSize) {
    }

    public record StickyNoteSettings(String backgroundColor, String borderColor, String textColor) {

        @JsonProperty
        public String defaultBackgroundColor() {
            return NasProperties.StickyNotes.DEFAULT_BACKGROUND_COLOR;
        }

        @JsonProperty
        public String defaultBorderColor() {
            return NasProperties.StickyNotes.DEFAULT_BORDER_COLOR;
        }

        @JsonProperty
        public String defaultTextColor() {
            return NasProperties.StickyNotes.DEFAULT_TEXT_COLOR;
        }
    }

    public record StorageSettings(String defaultConflictPolicy) {
    }

    public record RecentSettings(int maxItems, boolean recordDirectories) {
    }

    public record TrashSettings(int retentionDays, boolean cleanupOnStartup, long cleanupIntervalMs) {

        @JsonProperty
        public String cleanupIntervalMinutes() {
            return scaledDisplay(cleanupIntervalMs, MINUTE_MS);
        }
    }

    public record FileToolSettings(
            long textAutoLoadMaxBytes,
            long textManualLoadMaxBytes,
            long textDraftRetentionHours,
            long textDraftCleanupIntervalMs,
            long textDraftLeaseSeconds,
            int comicMaxPages,
            long comicPageMaxBytes,
            long comicInfoMaxBytes
    ) {

        @JsonProperty
        public String textAutoLoadMaxMib() {
            return scaledDisplay(textAutoLoadMaxBytes, MIB);
        }

        @JsonProperty
        public String textManualLoadMaxMib() {
            return scaledDisplay(textManualLoadMaxBytes, MIB);
        }

        @JsonProperty
        public String textDraftCleanupIntervalMinutes() {
            return scaledDisplay(textDraftCleanupIntervalMs, MINUTE_MS);
        }

        @JsonProperty
        public String comicPageMaxMib() {
            return scaledDisplay(comicPageMaxBytes, MIB);
        }

        @JsonProperty
        public String comicInfoMaxKib() {
            return scaledDisplay(comicInfoMaxBytes, KIB);
        }
    }

    public record RemoteDownloadSettings(
            boolean enabled,
            boolean directEnabled,
            boolean blockPrivateNetworks,
            String allowedPorts,
            int responseTimeoutSeconds,
            int maxRedirects,
            long maxFileSizeBytes,
            int historyLimit,
            int maxRetries,
            boolean skipInspectByDefault,
            String defaultTargetDirectory
    ) {

        @JsonProperty
        public String maxFileSizeGib() {
            return scaledDisplay(maxFileSizeBytes, GIB);
        }
    }
}
