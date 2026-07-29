package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class BookmarkSettingsService {

    private static final List<String> LINK_CLICK_ACTIONS = List.of("open", "detail");

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;

    public BookmarkSettingsService(NasProperties nasProperties, LocalPropertiesFile localPropertiesFile) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
    }

    public BookmarkSettingsSnapshot currentSettings() {
        NasProperties.Bookmarks bookmarks = nasProperties.getBookmarks();
        return new BookmarkSettingsSnapshot(
                new BehaviorSettings(bookmarks.getLinkClickAction()),
                new MetadataSettings(
                        bookmarks.isMetadataFetchEnabled(),
                        bookmarks.getMetadataNetworkRoute(),
                        bookmarks.isBlockPrivateNetworks(),
                        join(bookmarks.getAllowedPorts()),
                        bookmarks.getConnectTimeoutSeconds(),
                        bookmarks.getResponseTimeoutSeconds(),
                        bookmarks.getMaxRedirects(),
                        bookmarks.getHtmlMaxBytes(),
                        bookmarks.getFaviconMaxBytes()
                ),
                new CacheSettings(bookmarks.getFaviconCacheDirectory()),
                localPropertiesFile.configFile().toString()
        );
    }

    public BookmarkSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        String linkClickAction = oneOf(clean(first(parameters, "linkClickAction")), LINK_CLICK_ACTIONS, "Link click action");
        boolean metadataFetchEnabled = parameters.containsKey("metadataFetchEnabled");
        NetworkRoute metadataNetworkRoute = NetworkRoute.fromSetting(first(parameters, "metadataNetworkRoute"));
        boolean blockPrivateNetworks = parameters.containsKey("blockPrivateNetworks");
        List<Integer> allowedPorts = allowedPorts(first(parameters, "allowedPorts"));
        int connectTimeoutSeconds = intRange(first(parameters, "connectTimeoutSeconds"), 1, 3600, "Connect timeout");
        int responseTimeoutSeconds = intRange(first(parameters, "responseTimeoutSeconds"), 1, 3600, "Response timeout");
        int maxRedirects = intRange(first(parameters, "maxRedirects"), 0, 50, "Max redirects");
        int htmlMaxBytes = intRange(first(parameters, "htmlMaxBytes"), 1024, Integer.MAX_VALUE, "HTML max bytes");
        int faviconMaxBytes = intRange(first(parameters, "faviconMaxBytes"), 1024, Integer.MAX_VALUE, "Favicon max bytes");
        String faviconCacheDirectory = safeCacheDirectory(first(parameters, "faviconCacheDirectory"));

        return new BookmarkSettingsUpdate(
                new BehaviorSettings(linkClickAction),
                new MetadataSettings(
                        metadataFetchEnabled,
                        metadataNetworkRoute,
                        blockPrivateNetworks,
                        join(allowedPorts),
                        connectTimeoutSeconds,
                        responseTimeoutSeconds,
                        maxRedirects,
                        htmlMaxBytes,
                        faviconMaxBytes
                ),
                new CacheSettings(faviconCacheDirectory),
                allowedPorts
        );
    }

    public void save(BookmarkSettingsUpdate update) throws IOException {
        persist(update);
        applyToRuntime(update);
    }

    private void persist(BookmarkSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        BehaviorSettings behavior = update.behavior();
        updates.put("nas.bookmarks.link-click-action", behavior.linkClickAction());

        MetadataSettings metadata = update.metadata();
        updates.put("nas.bookmarks.metadata-fetch-enabled", Boolean.toString(metadata.metadataFetchEnabled()));
        updates.put("nas.bookmarks.metadata-network-route", metadata.metadataNetworkRoute().settingValue());
        updates.put("nas.bookmarks.block-private-networks", Boolean.toString(metadata.blockPrivateNetworks()));
        updates.put("nas.bookmarks.allowed-ports", metadata.allowedPorts());
        updates.put("nas.bookmarks.connect-timeout-seconds", Integer.toString(metadata.connectTimeoutSeconds()));
        updates.put("nas.bookmarks.response-timeout-seconds", Integer.toString(metadata.responseTimeoutSeconds()));
        updates.put("nas.bookmarks.max-redirects", Integer.toString(metadata.maxRedirects()));
        updates.put("nas.bookmarks.html-max-bytes", Integer.toString(metadata.htmlMaxBytes()));
        updates.put("nas.bookmarks.favicon-max-bytes", Integer.toString(metadata.faviconMaxBytes()));

        CacheSettings cache = update.cache();
        updates.put("nas.bookmarks.favicon-cache-directory", cache.faviconCacheDirectory());

        localPropertiesFile.update(updates, "# Bookmark settings managed from EnderVault Settings.");
    }

    private void applyToRuntime(BookmarkSettingsUpdate update) {
        NasProperties.Bookmarks bookmarks = nasProperties.getBookmarks();
        bookmarks.setLinkClickAction(update.behavior().linkClickAction());
        bookmarks.setMetadataFetchEnabled(update.metadata().metadataFetchEnabled());
        bookmarks.setMetadataNetworkRoute(update.metadata().metadataNetworkRoute());
        bookmarks.setBlockPrivateNetworks(update.metadata().blockPrivateNetworks());
        bookmarks.setAllowedPorts(update.allowedPorts());
        bookmarks.setConnectTimeoutSeconds(update.metadata().connectTimeoutSeconds());
        bookmarks.setResponseTimeoutSeconds(update.metadata().responseTimeoutSeconds());
        bookmarks.setMaxRedirects(update.metadata().maxRedirects());
        bookmarks.setHtmlMaxBytes(update.metadata().htmlMaxBytes());
        bookmarks.setFaviconMaxBytes(update.metadata().faviconMaxBytes());
        bookmarks.setFaviconCacheDirectory(update.cache().faviconCacheDirectory());
    }

    private static String safeCacheDirectory(String rawValue) {
        String value = clean(rawValue);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Favicon cache directory is required.");
        }
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Favicon cache directory must be a simple directory name.");
        }
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Favicon cache directory may contain only letters, numbers, dot, underscore, and hyphen.");
        }
        return value;
    }

    private static List<Integer> allowedPorts(String rawValue) {
        String value = clean(rawValue);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Allowed ports is required.");
        }
        List<Integer> ports = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(port -> !port.isBlank())
                .map(port -> intRange(port, 1, 65535, "Allowed port"))
                .distinct()
                .toList();
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("Allowed ports is required.");
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
        try {
            int value = Integer.parseInt(clean(rawValue));
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

    public record BookmarkSettingsSnapshot(
            BehaviorSettings behavior,
            MetadataSettings metadata,
            CacheSettings cache,
            String configPath
    ) {
    }

    public record BookmarkSettingsUpdate(
            BehaviorSettings behavior,
            MetadataSettings metadata,
            CacheSettings cache,
            List<Integer> allowedPorts
    ) {
    }

    public record BehaviorSettings(String linkClickAction) {
    }

    public record MetadataSettings(
            boolean metadataFetchEnabled,
            NetworkRoute metadataNetworkRoute,
            boolean blockPrivateNetworks,
            String allowedPorts,
            int connectTimeoutSeconds,
            int responseTimeoutSeconds,
            int maxRedirects,
            int htmlMaxBytes,
            int faviconMaxBytes
    ) {
    }

    public record CacheSettings(String faviconCacheDirectory) {
    }
}
