package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class AdvancedSettingsService {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;

    public AdvancedSettingsService(NasProperties nasProperties, LocalPropertiesFile localPropertiesFile) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
    }

    public AdvancedSettingsSnapshot currentSettings() {
        Map<String, String> configured = configuredValues();
        Map<String, List<SettingField>> fieldsByGroup = new LinkedHashMap<>();
        for (Definition definition : definitions()) {
            String persistedValue = definition.mode() == ApplyMode.RESTART
                    ? configured.getOrDefault(definition.key(), definition.runtimeValue().get())
                    : definition.runtimeValue().get();
            SettingField field = new SettingField(
                    definition.name(),
                    definition.label(),
                    definition.description(),
                    definition.type().name().toLowerCase(Locale.ROOT),
                    definition.toDisplay().apply(persistedValue),
                    definition.min(),
                    definition.max(),
                    definition.step(),
                    definition.unit(),
                    definition.mode() == ApplyMode.RESTART,
                    confirmationRequired(definition.key()),
                    definition.choices()
            );
            fieldsByGroup.computeIfAbsent(definition.groupId(), ignored -> new ArrayList<>()).add(field);
        }

        List<SettingGroup> groups = groupMetadata().stream()
                .map(group -> new SettingGroup(
                        group.id(),
                        group.title(),
                        group.description(),
                        List.copyOf(fieldsByGroup.getOrDefault(group.id(), List.of()))
                ))
                .toList();
        return new AdvancedSettingsSnapshot(
                groups,
                deploymentValues(),
                localPropertiesFile.configFile().toString()
        );
    }

    public AdvancedSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Definition definition : definitions()) {
            String rawValue = definition.type() == InputType.BOOLEAN
                    ? Boolean.toString(parameters.containsKey(definition.name()))
                    : first(parameters, definition.name());
            values.put(definition.key(), definition.normalize().apply(rawValue));
        }
        validateRelationships(values);
        return new AdvancedSettingsUpdate(Map.copyOf(values));
    }

    public boolean save(AdvancedSettingsUpdate update) throws IOException {
        Map<String, String> configuredBeforeSave = configuredValues();
        boolean restartRequired = definitions().stream()
                .filter(definition -> definition.mode() == ApplyMode.RESTART)
                .anyMatch(definition -> {
                    String previous = configuredBeforeSave.getOrDefault(
                            definition.key(),
                            definition.runtimeValue().get()
                    );
                    return !previous.equals(update.values().get(definition.key()));
                });

        localPropertiesFile.update(
                update.values(),
                "# Advanced settings managed from EnderVault Settings."
        );

        for (Definition definition : definitions()) {
            String value = update.values().get(definition.key());
            if (definition.mode() == ApplyMode.RESTART) {
                continue;
            }
            if (definition.runtimeApply() != null) {
                definition.runtimeApply().accept(value);
            }
        }
        return restartRequired;
    }

    private List<Definition> definitions() {
        NasProperties.Share share = nasProperties.getShare();
        NasProperties.Upload upload = nasProperties.getUpload();
        NasProperties.Thumbnails thumbnails = nasProperties.getThumbnails();
        NasProperties.FileTools fileTools = nasProperties.getFileTools();
        NasProperties.RemoteDownload remote = nasProperties.getRemoteDownload();
        NasProperties.PendingFileDecisions pending = nasProperties.getPendingFileDecisions();
        NasProperties.TemporaryArtifacts temporary = nasProperties.getTemporaryArtifacts();
        NasProperties.Tasks tasks = nasProperties.getTasks();
        NasProperties.MetadataInspector metadata = nasProperties.getMetadataInspector();
        NasProperties.ActivityLog activity = nasProperties.getActivityLog();
        NasProperties.Passkeys passkeys = nasProperties.getPasskeys();

        return List.of(
                bool("sharing", "nas.share.enabled", "shareEnabled", "Share links", "Allow administrators to create and use share links.", ApplyMode.RUNTIME,
                        share::isEnabled, share::setEnabled),
                number("sharing", "nas.share.default-expiration-days", "shareDefaultExpirationDays", "Default expiration", "Days before a new link expires. Use 0 for no expiration.", "days", 0, 36500, ApplyMode.RUNTIME,
                        share::getDefaultExpirationDays, share::setDefaultExpirationDays),
                bool("sharing", "nas.share.allow-never-expires", "shareAllowNeverExpires", "Allow links without expiration", "Permit administrators to create links that never expire.", ApplyMode.RUNTIME,
                        share::isAllowNeverExpires, share::setAllowNeverExpires),
                number("sharing", "nas.share.max-expiration-days", "shareMaxExpirationDays", "Maximum expiration", "Maximum allowed lifetime in days. Use 0 for no upper limit.", "days", 0, 36500, ApplyMode.RUNTIME,
                        share::getMaxExpirationDays, share::setMaxExpirationDays),
                bool("sharing", "nas.share.custom-token-enabled", "shareCustomTokenEnabled", "Custom tokens", "Allow an administrator to choose the public link token.", ApplyMode.RUNTIME,
                        share::isCustomTokenEnabled, share::setCustomTokenEnabled),
                number("sharing", "nas.share.custom-token-min-length", "shareCustomTokenMinLength", "Minimum custom token length", "Shortest permitted custom token.", "characters", 1, 128, ApplyMode.RUNTIME,
                        share::getCustomTokenMinLength, share::setCustomTokenMinLength),
                number("sharing", "nas.share.custom-token-max-length", "shareCustomTokenMaxLength", "Maximum custom token length", "Longest permitted custom token.", "characters", 1, 256, ApplyMode.RUNTIME,
                        share::getCustomTokenMaxLength, share::setCustomTokenMaxLength),
                number("sharing", "nas.share.random-token-bytes", "shareRandomTokenBytes", "Random token entropy", "Number of random bytes used for generated tokens.", "bytes", 8, 64, ApplyMode.RUNTIME,
                        share::getRandomTokenBytes, share::setRandomTokenBytes),
                bool("sharing", "nas.share.directory-share-enabled", "shareDirectoryEnabled", "Directory sharing", "Allow a whole directory to be shared.", ApplyMode.RUNTIME,
                        share::isDirectoryShareEnabled, share::setDirectoryShareEnabled),
                bool("sharing", "nas.share.direct-download-link-enabled", "shareDirectDownloadEnabled", "Direct download links", "Expose a copyable direct-download URL for shared files.", ApplyMode.RUNTIME,
                        share::isDirectDownloadLinkEnabled, share::setDirectDownloadLinkEnabled),
                bool("sharing", "nas.share.directory-show-hidden-items", "shareShowHiddenItems", "Show hidden items in shared directories", "Hidden descendants remain inaccessible when this is disabled.", ApplyMode.RUNTIME,
                        share::isDirectoryShowHiddenItems, share::setDirectoryShowHiddenItems),

                scaledNumber("transfer", "nas.upload.resumable-chunk-size-bytes", "uploadChunkSizeMib", "Upload chunk size", "Size of each resumable upload request. Reverse-proxy limits must be larger.", "MiB", 1, 64, 1, MIB, ApplyMode.RUNTIME,
                        upload::getResumableChunkSizeBytes, upload::setResumableChunkSizeBytes),
                number("transfer", "nas.upload.max-concurrent-chunks", "uploadConcurrentChunks", "Concurrent upload chunks", "Global number of chunks that may be written at once.", "chunks", 1, 16, ApplyMode.RESTART,
                        upload::getMaxConcurrentChunks, null),
                number("transfer", "nas.upload.resumable-session-retention-hours", "uploadSessionRetentionHours", "Interrupted upload retention", "How long resumable upload state is kept after interruption.", "hours", 1, 168, ApplyMode.RUNTIME,
                        upload::getResumableSessionRetentionHours, upload::setResumableSessionRetentionHours),
                scaledNumber("transfer", "nas.upload.resumable-cleanup-interval-ms", "uploadCleanupIntervalMinutes", "Upload cleanup interval", "How often abandoned resumable sessions are inspected.", "minutes", 1, 10080, 1, 60000L, ApplyMode.RESTART,
                        upload::getResumableCleanupIntervalMs),
                bool("transfer", "nas.thumbnails.video-enabled", "thumbnailVideoEnabled", "Video thumbnails", "Generate and cache a representative video frame.", ApplyMode.RESTART,
                        thumbnails::isVideoEnabled, null),
                bool("transfer", "nas.thumbnails.comic-enabled", "thumbnailComicEnabled", "Comic thumbnails", "Use the first supported CBZ image as the thumbnail.", ApplyMode.RESTART,
                        thumbnails::isComicEnabled, null),
                bool("transfer", "nas.thumbnails.pdf-enabled", "thumbnailPdfEnabled", "PDF thumbnails", "Render the first page of supported PDF files.", ApplyMode.RESTART,
                        thumbnails::isPdfEnabled, null),
                text("transfer", "nas.thumbnails.cache-directory", "thumbnailCacheDirectory", "Thumbnail cache directory", "Simple directory name below EnderVault metadata.", ApplyMode.RESTART,
                        thumbnails::getCacheDirectory, AdvancedSettingsService::simpleDirectory),
                number("transfer", "nas.thumbnails.generator-threads", "thumbnailGeneratorThreads", "Thumbnail workers", "Maximum number of thumbnail jobs processed concurrently.", "threads", 1, 16, ApplyMode.RESTART,
                        thumbnails::getGeneratorThreads, null),
                number("transfer", "nas.remote-download.connect-timeout-seconds", "remoteConnectTimeoutSeconds", "Remote connect timeout", "Time allowed to establish a remote-download connection.", "seconds", 1, 3600, ApplyMode.RUNTIME,
                        remote::getConnectTimeoutSeconds, remote::setConnectTimeoutSeconds),
                number("transfer", "nas.remote-download.worker-threads", "remoteWorkerThreads", "Remote download workers", "Number of remote-download tasks that may execute concurrently.", "threads", 1, 8, ApplyMode.RESTART,
                        remote::getWorkerThreads, null),

                number("archive", "nas.file-tools.archive-max-entries", "archiveMaxEntries", "Maximum entries", "Reject archives with more entries than this limit.", "entries", 1, 1000000, ApplyMode.RESTART,
                        fileTools::getArchiveMaxEntries, null),
                scaledDecimal("archive", "nas.file-tools.archive-entry-max-bytes", "archiveEntryMaxGib", "Maximum extracted entry", "Maximum uncompressed size of one archive entry.", "GiB", new BigDecimal("0.001"), new BigDecimal("10240"), new BigDecimal("0.25"), GIB, ApplyMode.RESTART,
                        fileTools::getArchiveEntryMaxBytes),
                scaledDecimal("archive", "nas.file-tools.archive-total-max-bytes", "archiveTotalMaxGib", "Maximum extracted total", "Maximum combined uncompressed size of an archive.", "GiB", new BigDecimal("0.001"), new BigDecimal("102400"), new BigDecimal("1"), GIB, ApplyMode.RESTART,
                        fileTools::getArchiveTotalMaxBytes),
                number("archive", "nas.file-tools.archive-max-compression-ratio", "archiveMaxCompressionRatio", "Maximum compression ratio", "Reject entries whose declared compression ratio exceeds this value.", "ratio", 1, 1000000, ApplyMode.RESTART,
                        fileTools::getArchiveMaxCompressionRatio, null),
                scaledNumber("archive", "nas.file-tools.archive-max-memory-kib", "archiveMaxMemoryMib", "Maximum archive memory", "Memory ceiling used by archive readers that support one.", "MiB", 1, 65536, 1, 1024L, ApplyMode.RESTART,
                        fileTools::getArchiveMaxMemoryKiB),
                number("archive", "nas.file-tools.archive-manifest-cache-entries", "archiveManifestCacheEntries", "Manifest cache entries", "Number of parsed archive manifests retained in memory.", "archives", 1, 512, ApplyMode.RESTART,
                        fileTools::getArchiveManifestCacheEntries, null),

                number("operations", "nas.pending-file-decisions.warning-days", "pendingWarningDays", "Pending decision warning age", "Age at which unresolved file decisions are highlighted by metadata inspection.", "days", 1, 3650, ApplyMode.RESTART,
                        pending::getWarningDays, null),
                number("operations", "nas.temporary-artifacts.stale-after-minutes", "temporaryStaleMinutes", "Temporary artifact stale age", "Inactive, unregistered staging artifacts older than this are reported as stale.", "minutes", 1, 525600, ApplyMode.RUNTIME,
                        temporary::getStaleAfterMinutes, temporary::setStaleAfterMinutes),
                number("operations", "nas.tasks.history-limit", "taskHistoryLimit", "Task history limit", "Maximum completed task records retained in memory.", "tasks", 1, 10000, ApplyMode.RUNTIME,
                        tasks::getHistoryLimit, tasks::setHistoryLimit),
                number("operations", "nas.tasks.worker-threads", "taskWorkerThreads", "Background task workers", "Size of the shared background-task executor.", "threads", 1, 16, ApplyMode.RESTART,
                        tasks::getWorkerThreads, null),
                bool("operations", "nas.tasks.activity-panel-enabled", "taskActivityPanelEnabled", "Activity panel", "Show background task progress in the global activity dock.", ApplyMode.RUNTIME,
                        tasks::isActivityPanelEnabled, tasks::setActivityPanelEnabled),
                scaledDecimal("operations", "nas.tasks.completed-display-ms", "taskCompletedDisplaySeconds", "Completed task display time", "How long completed tasks remain visible in the dock.", "seconds", BigDecimal.ZERO, new BigDecimal("3600"), new BigDecimal("0.1"), 1000L, ApplyMode.RUNTIME,
                        tasks::getCompletedDisplayMs, value -> tasks.setCompletedDisplayMs(Math.toIntExact(value))),
                scaledDecimal("operations", "nas.tasks.failed-display-ms", "taskFailedDisplaySeconds", "Failed task display time", "How long failed tasks remain visible in the dock.", "seconds", BigDecimal.ZERO, new BigDecimal("3600"), new BigDecimal("0.1"), 1000L, ApplyMode.RUNTIME,
                        tasks::getFailedDisplayMs, value -> tasks.setFailedDisplayMs(Math.toIntExact(value))),
                bool("operations", "nas.metadata-inspector.enabled", "metadataInspectorEnabled", "Metadata Inspector", "Allow administrators to scan and repair metadata areas.", ApplyMode.RUNTIME,
                        metadata::isEnabled, metadata::setEnabled),
                number("operations", "nas.metadata-inspector.max-issues-per-area", "metadataMaxIssues", "Maximum issues per area", "Stop collecting findings after this many issues in one area. Use 0 for no findings.", "issues", 0, 1000000, ApplyMode.RUNTIME,
                        metadata::getMaxIssuesPerArea, metadata::setMaxIssuesPerArea),
                bool("operations", "nas.activity-log.enabled", "activityLogEnabled", "Activity logging", "Record supported administrative and public-link activity.", ApplyMode.RUNTIME,
                        activity::isEnabled, activity::setEnabled),
                scaledNumber("operations", "nas.activity-log.max-file-size-bytes", "activityLogMaxFileMib", "Activity log file size", "Roll the current JSONL log after it reaches this size.", "MiB", 1, 102400, 1, MIB, ApplyMode.RUNTIME,
                        activity::getMaxFileSizeBytes, activity::setMaxFileSizeBytes),
                number("operations", "nas.activity-log.max-archive-files", "activityLogMaxArchives", "Activity log archives", "Maximum rolled log files retained. Use 0 to disable automatic archive deletion.", "files", 0, 100000, ApplyMode.RUNTIME,
                        activity::getMaxArchiveFiles, activity::setMaxArchiveFiles),
                number("operations", "nas.activity-log.default-page-size", "activityLogDefaultPageSize", "Default activity page size", "Initial number of activity rows displayed.", "rows", 1, 1000, ApplyMode.RUNTIME,
                        activity::getDefaultPageSize, activity::setDefaultPageSize),
                csvIntegers("operations", "nas.activity-log.page-size-options", "activityLogPageSizeOptions", "Activity page size choices", "Comma-separated choices offered by the activity log page.", 1, 1000, ApplyMode.RUNTIME,
                        activity::getPageSizeOptions, activity::setPageSizeOptions),
                bool("operations", "nas.activity-log.allow-archive-delete", "activityLogAllowArchiveDelete", "Allow archived log deletion", "Permit deletion of rolled activity logs from the web UI.", ApplyMode.RUNTIME,
                        activity::isAllowArchiveDelete, activity::setAllowArchiveDelete),

                text("access", "nas.server.public-base-url", "publicBaseUrl", "Public base URL", "Optional externally reachable base URL used when generating public links.", ApplyMode.RUNTIME,
                        () -> nasProperties.getServer().getPublicBaseUrl(), AdvancedSettingsService::optionalHttpUrl,
                        value -> nasProperties.getServer().setPublicBaseUrl(value)),
                textarea("access", "nas.security.trusted-proxies", "trustedProxies", "Trusted reverse proxies", "IP literals or CIDR blocks allowed to supply X-Forwarded-For, separated by commas or lines.", ApplyMode.RESTART,
                        () -> String.join(",", nasProperties.getSecurity().getTrustedProxies()), AdvancedSettingsService::trustedProxies),
                bool("access", "nas.passkeys.enabled", "passkeysEnabled", "Passkey login", "Enable WebAuthn passkey registration and login endpoints.", ApplyMode.RESTART,
                        passkeys::isEnabled, null),
                text("access", "nas.passkeys.rp-id", "passkeyRpId", "Passkey RP ID", "Domain bound into passkey credentials. Changing it invalidates existing credentials for login.", ApplyMode.RESTART,
                        passkeys::getRpId, AdvancedSettingsService::passkeyRpId),
                text("access", "nas.passkeys.rp-name", "passkeyRpName", "Passkey RP name", "Human-readable vault name shown by authenticators.", ApplyMode.RESTART,
                        passkeys::getRpName, value -> requiredOneLine(value, "Passkey RP name", 100)),
                textarea("access", "nas.passkeys.allowed-origins", "passkeyAllowedOrigins", "Passkey allowed origins", "Exact HTTP(S) origins accepted during WebAuthn ceremonies, separated by commas or lines.", ApplyMode.RESTART,
                        () -> String.join(",", passkeys.getAllowedOrigins()), AdvancedSettingsService::allowedOrigins),
                select("access", "nas.outbound.initial-route", "outboundInitialRoute", "Initial outbound route", "Route selected whenever EnderVault starts. VPN requires VPN egress to be enabled and healthy.", ApplyMode.RESTART,
                        () -> nasProperties.getOutbound().getInitialRoute().settingValue(),
                        List.of(new SettingChoice("direct", "Direct"), new SettingChoice("vpn-required", "VPN required")))
        );
    }

    private List<ReadOnlySetting> deploymentValues() {
        NasProperties.Storage storage = nasProperties.getStorage();
        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        return List.of(
                new ReadOnlySetting("Configuration file", localPropertiesFile.configFile().toString(), "Local values saved by the web UI."),
                new ReadOnlySetting("Storage root", storage.getRoot().toAbsolutePath().normalize().toString(), "Managed by the process environment or Docker volume mapping."),
                new ReadOnlySetting("Trash directory", storage.getTrashDirectory(), "Changing metadata topology while running is intentionally unsupported."),
                new ReadOnlySetting("Metadata directory", storage.getMetadataDirectory(), "Contains EnderVault registries, logs, drafts, and caches."),
                new ReadOnlySetting("Setup accepted", Boolean.toString(nasProperties.getSetup().isAccepted()), "Startup gate; edit the local configuration file before launching."),
                new ReadOnlySetting("VPN control URL", blankLabel(vpn.getControlUrl()), "Managed by the Docker Compose network or deployment environment."),
                new ReadOnlySetting("VPN control key file", blankLabel(vpn.getControlApiKeyFile()), "Only the secret file path is shown; secret contents are never exposed."),
                new ReadOnlySetting("VPN control timeout", vpn.getControlRequestTimeoutMs() + " ms", "Control-plane request timeout fixed when the application starts."),
                new ReadOnlySetting("VPN profile", blankLabel(vpn.getProfileName()), "Control-plane profile supplied by the VPN container.")
        );
    }

    private void validateRelationships(Map<String, String> values) {
        int tokenMin = Integer.parseInt(values.get("nas.share.custom-token-min-length"));
        int tokenMax = Integer.parseInt(values.get("nas.share.custom-token-max-length"));
        if (tokenMin > tokenMax) {
            throw new IllegalArgumentException("Share token minimum length cannot exceed its maximum length.");
        }

        int defaultExpiration = Integer.parseInt(values.get("nas.share.default-expiration-days"));
        int maxExpiration = Integer.parseInt(values.get("nas.share.max-expiration-days"));
        boolean allowNever = Boolean.parseBoolean(values.get("nas.share.allow-never-expires"));
        if (!allowNever && defaultExpiration == 0) {
            throw new IllegalArgumentException("Default share expiration must be greater than zero when never-expiring links are disabled.");
        }
        if (maxExpiration > 0 && defaultExpiration > maxExpiration) {
            throw new IllegalArgumentException("Default share expiration cannot exceed the maximum expiration.");
        }

        long archiveEntry = Long.parseLong(values.get("nas.file-tools.archive-entry-max-bytes"));
        long archiveTotal = Long.parseLong(values.get("nas.file-tools.archive-total-max-bytes"));
        if (archiveEntry > archiveTotal) {
            throw new IllegalArgumentException("Archive total size limit must be at least the single-entry limit.");
        }

        int activityDefault = Integer.parseInt(values.get("nas.activity-log.default-page-size"));
        List<Integer> activityOptions = parseIntegerList(values.get("nas.activity-log.page-size-options"), 1, 1000, "Activity page size option");
        if (!activityOptions.contains(activityDefault)) {
            throw new IllegalArgumentException("Activity page size choices must include the default page size.");
        }

        boolean passkeysEnabled = Boolean.parseBoolean(values.get("nas.passkeys.enabled"));
        if (!passkeysEnabled && !nasProperties.getPasskeys().isPasswordLoginEnabled()) {
            throw new IllegalArgumentException("Enable ID/password login before disabling passkey login.");
        }
    }

    private Map<String, String> configuredValues() {
        try {
            return localPropertiesFile.readValues();
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    private static List<GroupMetadata> groupMetadata() {
        return List.of(
                new GroupMetadata("sharing", "Share Links", "Defaults and security boundaries for public read-only links."),
                new GroupMetadata("transfer", "Transfer and Thumbnails", "Resumable upload, remote worker, and thumbnail engine settings."),
                new GroupMetadata("archive", "Archive Safety", "Extraction limits and manifest caching for ZIP-compatible file tools."),
                new GroupMetadata("operations", "Operations and Maintenance", "Task history, temporary artifacts, metadata inspection, and activity logs."),
                new GroupMetadata("access", "Access and Network Identity", "Public URL, trusted proxies, passkey identity, and startup route.")
        );
    }

    private static Definition bool(
            String group, String key, String name, String label, String description, ApplyMode mode,
            Supplier<Boolean> value, Consumer<Boolean> apply
    ) {
        return definition(group, key, name, label, description, InputType.BOOLEAN, mode,
                () -> Boolean.toString(value.get()), AdvancedSettingsService::booleanValue,
                apply == null ? null : raw -> apply.accept(Boolean.parseBoolean(raw)), "", "", "", "", List.of());
    }

    private static Definition number(
            String group, String key, String name, String label, String description, String unit,
            long min, long max, ApplyMode mode, Supplier<? extends Number> value, Consumer<Integer> apply
    ) {
        return definition(group, key, name, label, description, InputType.NUMBER, mode,
                () -> String.valueOf(value.get()), raw -> Long.toString(longRange(raw, min, max, label)),
                apply == null ? null : raw -> apply.accept(Math.toIntExact(Long.parseLong(raw))),
                Long.toString(min), Long.toString(max), "1", unit, List.of());
    }

    private static Definition scaledNumber(
            String group, String key, String name, String label, String description, String unit,
            long min, long max, long step, long scale, ApplyMode mode, Supplier<? extends Number> value
    ) {
        return scaledDecimal(group, key, name, label, description, unit,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), BigDecimal.valueOf(step), scale, mode, value, null);
    }

    private static Definition scaledNumber(
            String group, String key, String name, String label, String description, String unit,
            long min, long max, long step, long scale, ApplyMode mode, Supplier<? extends Number> value,
            Consumer<Long> apply
    ) {
        return scaledDecimal(group, key, name, label, description, unit,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), BigDecimal.valueOf(step), scale, mode, value, apply);
    }

    private static Definition scaledDecimal(
            String group, String key, String name, String label, String description, String unit,
            BigDecimal min, BigDecimal max, BigDecimal step, long scale, ApplyMode mode,
            Supplier<? extends Number> value
    ) {
        return scaledDecimal(group, key, name, label, description, unit, min, max, step, scale, mode, value, null);
    }

    private static Definition scaledDecimal(
            String group, String key, String name, String label, String description, String unit,
            BigDecimal min, BigDecimal max, BigDecimal step, long scale, ApplyMode mode,
            Supplier<? extends Number> value, Consumer<Long> apply
    ) {
        Function<String, String> normalize = raw -> {
            BigDecimal display = decimalRange(raw, min, max, label);
            try {
                return display.multiply(BigDecimal.valueOf(scale)).longValueExact() + "";
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(label + " has too much precision.");
            }
        };
        Function<String, String> display = raw -> BigDecimal.valueOf(Long.parseLong(raw))
                .divide(BigDecimal.valueOf(scale))
                .stripTrailingZeros()
                .toPlainString();
        return new Definition(group, key, name, label, description, InputType.NUMBER, mode,
                () -> String.valueOf(value.get()), normalize,
                apply == null ? null : raw -> apply.accept(Long.parseLong(raw)), display,
                min.toPlainString(), max.toPlainString(), step.toPlainString(), unit, List.of());
    }

    private static Definition text(
            String group, String key, String name, String label, String description, ApplyMode mode,
            Supplier<String> value, Function<String, String> normalize
    ) {
        return text(group, key, name, label, description, mode, value, normalize, null);
    }

    private static Definition text(
            String group, String key, String name, String label, String description, ApplyMode mode,
            Supplier<String> value, Function<String, String> normalize, Consumer<String> apply
    ) {
        return definition(group, key, name, label, description, InputType.TEXT, mode,
                value, normalize, apply, "", "", "", "", List.of());
    }

    private static Definition textarea(
            String group, String key, String name, String label, String description, ApplyMode mode,
            Supplier<String> value, Function<String, String> normalize
    ) {
        return definition(group, key, name, label, description, InputType.TEXTAREA, mode,
                value, normalize, null, "", "", "", "", List.of());
    }

    private static Definition csvIntegers(
            String group, String key, String name, String label, String description,
            int min, int max, ApplyMode mode, Supplier<List<Integer>> value, Consumer<List<Integer>> apply
    ) {
        return definition(group, key, name, label, description, InputType.TEXT, mode,
                () -> join(value.get()), raw -> join(parseIntegerList(raw, min, max, label)),
                normalized -> apply.accept(parseIntegerList(normalized, min, max, label)),
                "", "", "", "", List.of());
    }

    private static Definition select(
            String group, String key, String name, String label, String description, ApplyMode mode,
            Supplier<String> value, List<SettingChoice> choices
    ) {
        Function<String, String> normalize = raw -> choices.stream()
                .map(SettingChoice::value)
                .filter(option -> option.equalsIgnoreCase(cleanOneLine(raw)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(label + " is invalid."));
        return definition(group, key, name, label, description, InputType.SELECT, mode,
                value, normalize, null, "", "", "", "", choices);
    }

    private static Definition definition(
            String group, String key, String name, String label, String description, InputType type,
            ApplyMode mode, Supplier<String> value, Function<String, String> normalize,
            Consumer<String> apply, String min, String max, String step, String unit,
            List<SettingChoice> choices
    ) {
        return new Definition(group, key, name, label, description, type, mode, value, normalize,
                apply, Function.identity(), min, max, step, unit, choices);
    }

    private static String booleanValue(String raw) {
        return Boolean.toString(Boolean.parseBoolean(cleanOneLine(raw)));
    }

    private static long longRange(String raw, long min, long max, String label) {
        try {
            long value = Long.parseLong(cleanOneLine(raw));
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }

    private static BigDecimal decimalRange(String raw, BigDecimal min, BigDecimal max, String label) {
        try {
            BigDecimal value = new BigDecimal(cleanOneLine(raw));
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String requiredOneLine(String raw, String label, int maxLength) {
        String value = cleanOneLine(raw);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " is too long.");
        }
        return value;
    }

    private static String simpleDirectory(String raw) {
        String value = requiredOneLine(raw, "Cache directory", 128);
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Cache directory must be a simple directory name.");
        }
        return value;
    }

    private static String passkeyRpId(String raw) {
        String value = requiredOneLine(raw, "Passkey RP ID", 253).toLowerCase(Locale.ROOT);
        if (value.contains("://") || value.contains("/") || value.contains(":") || value.endsWith(".")) {
            throw new IllegalArgumentException("Passkey RP ID must be a host name without a scheme, port, or path.");
        }
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253 || Arrays.stream(ascii.split("\\.", -1))
                    .anyMatch(label -> label.isBlank() || label.length() > 63
                            || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))) {
                throw new IllegalArgumentException("Passkey RP ID must be a valid host name.");
            }
            return ascii;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Passkey RP ID must be a valid host name.");
        }
    }

    private static String optionalHttpUrl(String raw) {
        String value = cleanOneLine(raw);
        if (value.isBlank()) {
            return "";
        }
        URI uri = parseUri(value, "Public base URL");
        if (!isHttp(uri) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Public base URL must be an HTTP(S) URL without credentials, query, or fragment.");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String trustedProxies(String raw) {
        List<String> proxies = splitValues(raw);
        for (String proxy : proxies) {
            String[] parts = proxy.split("/", -1);
            if (parts.length > 2 || !looksLikeIpLiteral(parts[0])) {
                throw new IllegalArgumentException("Trusted proxies must contain only IP literals or CIDR blocks.");
            }
            try {
                InetAddress address = InetAddress.getByName(parts[0]);
                if (parts.length == 2) {
                    int prefix = Integer.parseInt(parts[1]);
                    int maxPrefix = address.getAddress().length * Byte.SIZE;
                    if (prefix < 0 || prefix > maxPrefix) {
                        throw new IllegalArgumentException("Trusted proxy CIDR prefix is invalid: " + proxy);
                    }
                }
            } catch (UnknownHostException | NumberFormatException ex) {
                throw new IllegalArgumentException("Trusted proxy is invalid: " + proxy);
            }
        }
        return String.join(",", proxies);
    }

    private static String allowedOrigins(String raw) {
        List<String> origins = splitValues(raw);
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("At least one passkey origin is required.");
        }
        for (String origin : origins) {
            URI uri = parseUri(origin, "Passkey origin");
            String path = uri.getPath();
            if (!isHttp(uri) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || (path != null && !path.isBlank() && !"/".equals(path))) {
                throw new IllegalArgumentException("Passkey origins must be exact HTTP(S) origins without a path.");
            }
        }
        return String.join(",", origins.stream().map(AdvancedSettingsService::canonicalOrigin).toList());
    }

    private static String canonicalOrigin(String value) {
        URI uri = parseUri(value, "Passkey origin");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port;
    }

    private static URI parseUri(String value, String label) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(label + " is invalid.");
        }
    }

    private static boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    private static boolean looksLikeIpLiteral(String value) {
        return value != null && (value.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || (value.contains(":") && value.matches("[0-9A-Fa-f:.%]+")));
    }

    private static List<String> splitValues(String raw) {
        return Arrays.stream((raw == null ? "" : raw).split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<Integer> parseIntegerList(String raw, int min, int max, String label) {
        List<Integer> values = splitValues(raw).stream()
                .map(value -> Math.toIntExact(longRange(value, min, max, label)))
                .distinct()
                .sorted()
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return values;
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String cleanOneLine(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static String join(List<Integer> values) {
        return values == null ? "" : String.join(",", values.stream().map(String::valueOf).toList());
    }

    private static String blankLabel(String value) {
        return value == null || value.isBlank() ? "Not configured" : value;
    }

    private static boolean confirmationRequired(String key) {
        return key.equals("nas.security.trusted-proxies")
                || key.equals("nas.passkeys.enabled")
                || key.equals("nas.passkeys.rp-id")
                || key.equals("nas.passkeys.allowed-origins");
    }

    private enum InputType {
        BOOLEAN,
        NUMBER,
        TEXT,
        TEXTAREA,
        SELECT
    }

    private enum ApplyMode {
        RUNTIME,
        RESTART
    }

    private record Definition(
            String groupId,
            String key,
            String name,
            String label,
            String description,
            InputType type,
            ApplyMode mode,
            Supplier<String> runtimeValue,
            Function<String, String> normalize,
            Consumer<String> runtimeApply,
            Function<String, String> toDisplay,
            String min,
            String max,
            String step,
            String unit,
            List<SettingChoice> choices
    ) {
    }

    private record GroupMetadata(String id, String title, String description) {
    }

    public record AdvancedSettingsSnapshot(
            List<SettingGroup> groups,
            List<ReadOnlySetting> deployment,
            String configPath
    ) {
    }

    public record AdvancedSettingsUpdate(Map<String, String> values) {
    }

    public record SettingGroup(String id, String title, String description, List<SettingField> fields) {
    }

    public record SettingField(
            String name,
            String label,
            String description,
            String type,
            String value,
            String min,
            String max,
            String step,
            String unit,
            boolean restartRequired,
            boolean confirmationRequired,
            List<SettingChoice> choices
    ) {
    }

    public record SettingChoice(String value, String label) {
    }

    public record ReadOnlySetting(String label, String value, String description) {
    }
}
