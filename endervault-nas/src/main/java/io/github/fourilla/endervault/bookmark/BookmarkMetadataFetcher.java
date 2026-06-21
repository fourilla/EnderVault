package io.github.fourilla.endervault.bookmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.ExternalUrlValidator;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class BookmarkMetadataFetcher {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern LINK_PATTERN =
            Pattern.compile("(?is)<link\\b[^>]*>");
    private static final Pattern META_PATTERN =
            Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script>");
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)");
    private static final List<String> TITLE_METADATA_KEYS = List.of(
            "og:title",
            "twitter:title",
            "title",
            "application-name",
            "apple-mobile-web-app-title"
    );

    private final NasProperties nasProperties;

    public BookmarkMetadataFetcher(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public BookmarkMetadataFetchResult fetch(String rawUrl) throws IOException, InterruptedException {
        NasProperties.Bookmarks bookmarks = nasProperties.getBookmarks();
        if (!bookmarks.isMetadataFetchEnabled()) {
            throw new StorageAccessException("Bookmark metadata fetch is disabled.");
        }
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.startsWith("/")) {
            throw new StorageAccessException("Bookmark metadata fetch supports external HTTP(S) links only.");
        }

        URI pageUri = validate(rawUrl);
        HtmlResponse htmlResponse = fetchHtml(pageUri);
        String title = extractTitle(htmlResponse.html());
        if (title.isBlank()) {
            title = fetchManifestTitle(htmlResponse.finalUri(), htmlResponse.html()).orElse("");
        }
        BookmarkMetadataFetchResult.Favicon favicon = fetchFirstFavicon(htmlResponse.finalUri(), htmlResponse.html())
                .orElse(null);
        return new BookmarkMetadataFetchResult(title, favicon);
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(nasProperties.getBookmarks().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HtmlResponse fetchHtml(URI uri) throws IOException, InterruptedException {
        URI current = validate(uri);
        int maxRedirects = nasProperties.getBookmarks().getMaxRedirects();
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(nasProperties.getBookmarks().getResponseTimeoutSeconds()))
                    .header("User-Agent", "EnderVault BookmarkMetadata")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new StorageAccessException("Bookmark metadata server returned a redirect without Location."));
                current = validateRedirect(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new StorageAccessException("Bookmark metadata server returned HTTP " + status + ".");
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank() && !isHtmlContentType(contentType)) {
                closeQuietly(response.body());
                throw new StorageAccessException("Bookmark metadata URL did not return HTML.");
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            int maxBytes = nasProperties.getBookmarks().getHtmlMaxBytes();
            if (contentLength > maxBytes) {
                closeQuietly(response.body());
                throw new StorageAccessException("Bookmark metadata HTML is too large.");
            }
            String html = new String(readLimited(response.body(), maxBytes), java.nio.charset.StandardCharsets.UTF_8);
            return new HtmlResponse(current, html);
        }
        throw new StorageAccessException("Bookmark metadata fetch exceeded the redirect limit.");
    }

    private Optional<BookmarkMetadataFetchResult.Favicon> fetchFirstFavicon(URI pageUri, String html) {
        List<URI> candidates = faviconCandidates(pageUri, html);
        for (URI candidate : candidates) {
            try {
                Optional<BookmarkMetadataFetchResult.Favicon> favicon = fetchFavicon(candidate);
                if (favicon.isPresent()) {
                    return favicon;
                }
            } catch (IOException | InterruptedException | StorageAccessException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                // Try the next candidate. A broken favicon should not fail title fetching.
            }
        }
        return Optional.empty();
    }

    private Optional<String> fetchManifestTitle(URI pageUri, String html) {
        for (URI candidate : manifestCandidates(pageUri, html)) {
            try {
                Optional<String> title = fetchManifestTitle(candidate);
                if (title.isPresent()) {
                    return title;
                }
            } catch (IOException | InterruptedException | StorageAccessException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                // Try the next candidate. A broken manifest should not fail bookmark creation.
            }
        }
        return Optional.empty();
    }

    private Optional<String> fetchManifestTitle(URI uri) throws IOException, InterruptedException {
        URI current = validate(uri);
        int maxRedirects = nasProperties.getBookmarks().getMaxRedirects();
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(nasProperties.getBookmarks().getResponseTimeoutSeconds()))
                    .header("User-Agent", "EnderVault BookmarkMetadata")
                    .header("Accept", "application/manifest+json,application/json,text/json,*/*;q=0.2")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new StorageAccessException("Bookmark manifest server returned a redirect without Location."));
                current = validateRedirect(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                return Optional.empty();
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank() && !isManifestContentType(contentType)) {
                closeQuietly(response.body());
                return Optional.empty();
            }

            int maxBytes = nasProperties.getBookmarks().getHtmlMaxBytes();
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                closeQuietly(response.body());
                return Optional.empty();
            }
            String manifest = new String(readLimited(response.body(), maxBytes), java.nio.charset.StandardCharsets.UTF_8);
            return titleFromManifest(manifest);
        }
        return Optional.empty();
    }

    private Optional<BookmarkMetadataFetchResult.Favicon> fetchFavicon(URI uri) throws IOException, InterruptedException {
        URI current = validate(uri);
        int maxRedirects = nasProperties.getBookmarks().getMaxRedirects();
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(nasProperties.getBookmarks().getResponseTimeoutSeconds()))
                    .header("User-Agent", "EnderVault BookmarkMetadata")
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,image/x-icon,*/*;q=0.2")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new StorageAccessException("Bookmark favicon server returned a redirect without Location."));
                current = validateRedirect(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                return Optional.empty();
            }

            String contentType = normalizeContentType(response.headers().firstValue("Content-Type").orElse(""));
            if (!allowedFaviconContentTypes().containsKey(contentType)) {
                closeQuietly(response.body());
                return Optional.empty();
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            int maxBytes = nasProperties.getBookmarks().getFaviconMaxBytes();
            if (contentLength > maxBytes) {
                closeQuietly(response.body());
                return Optional.empty();
            }
            byte[] bytes = readLimited(response.body(), maxBytes);
            return Optional.of(new BookmarkMetadataFetchResult.Favicon(
                    bytes,
                    contentType,
                    allowedFaviconContentTypes().get(contentType),
                    current.toString()
            ));
        }
        return Optional.empty();
    }

    private List<URI> faviconCandidates(URI pageUri, String html) {
        List<URI> candidates = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            String href = attributes.get("href");
            if (href == null || href.isBlank() || !rel.contains("icon")) {
                continue;
            }
            candidates.add(pageUri.resolve(HtmlUtils.htmlUnescape(href.trim())));
        }
        candidates.add(pageUri.resolve("/favicon.ico"));
        return candidates.stream().distinct().toList();
    }

    private List<URI> manifestCandidates(URI pageUri, String html) {
        List<URI> candidates = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            String href = attributes.get("href");
            if (href == null || href.isBlank() || !rel.contains("manifest")) {
                continue;
            }
            candidates.add(pageUri.resolve(HtmlUtils.htmlUnescape(href.trim())));
        }
        return candidates.stream().distinct().toList();
    }

    private Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2);
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            attributes.put(key, HtmlUtils.htmlUnescape(value.trim()));
        }
        return attributes;
    }

    private String extractTitle(String html) {
        for (String candidate : titleCandidates(html)) {
            String title = normalizeExtractedTitle(candidate);
            if (!title.isBlank()) {
                return title;
            }
        }
        return "";
    }

    private List<String> titleCandidates(String html) {
        List<String> candidates = new ArrayList<>();
        Map<String, String> metadataTitles = metadataTitles(html);
        addIfPresent(candidates, metadataTitles, "og:title");
        addIfPresent(candidates, metadataTitles, "twitter:title");
        addIfPresent(candidates, metadataTitles, "title");
        candidates.add(htmlTitle(html));
        candidates.addAll(jsonLdTitleCandidates(html));
        addIfPresent(candidates, metadataTitles, "application-name");
        addIfPresent(candidates, metadataTitles, "apple-mobile-web-app-title");
        return candidates;
    }

    private Map<String, String> metadataTitles(String html) {
        Map<String, String> titles = new LinkedHashMap<>();
        Matcher matcher = META_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String content = attributes.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            addMetadataTitle(titles, attributes.get("property"), content);
            addMetadataTitle(titles, attributes.get("name"), content);
            addMetadataTitle(titles, attributes.get("itemprop"), content);
        }
        return titles;
    }

    private void addMetadataTitle(Map<String, String> titles, String key, String content) {
        String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (TITLE_METADATA_KEYS.contains(normalizedKey)) {
            titles.putIfAbsent(normalizedKey, content);
        }
    }

    private void addIfPresent(List<String> candidates, Map<String, String> metadataTitles, String key) {
        String value = metadataTitles.get(key);
        if (value != null) {
            candidates.add(value);
        }
    }

    private String htmlTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private List<String> jsonLdTitleCandidates(String html) {
        List<String> titles = new ArrayList<>();
        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group(1));
            String type = attributes.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            if (!type.contains("ld+json")) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(stripJsonScriptWrappers(matcher.group(2)));
                collectJsonLdTitles(root, titles, 0);
            } catch (IOException | IllegalArgumentException ignored) {
                // Ignore malformed structured data; plain HTML title candidates may still work.
            }
        }
        return titles;
    }

    private void collectJsonLdTitles(JsonNode node, List<String> titles, int depth) {
        if (node == null || node.isMissingNode() || depth > 4 || titles.size() >= 10) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectJsonLdTitles(child, titles, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        addJsonText(titles, node.get("headline"));
        addJsonText(titles, node.get("name"));
        collectJsonLdTitles(node.get("@graph"), titles, depth + 1);
        collectJsonLdTitles(node.get("mainEntity"), titles, depth + 1);
        collectJsonLdTitles(node.get("about"), titles, depth + 1);
    }

    private void addJsonText(List<String> titles, JsonNode node) {
        if (node != null && node.isTextual()) {
            titles.add(node.asText());
        }
    }

    private Optional<String> titleFromManifest(String manifest) {
        try {
            JsonNode root = JSON.readTree(stripJsonScriptWrappers(manifest));
            String title = normalizeExtractedTitle(text(root.get("name")));
            if (!title.isBlank()) {
                return Optional.of(title);
            }
            title = normalizeExtractedTitle(text(root.get("short_name")));
            return title.isBlank() ? Optional.empty() : Optional.of(title);
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String stripJsonScriptWrappers(String value) {
        String normalized = value == null ? "" : HtmlUtils.htmlUnescape(value).trim();
        if (normalized.startsWith("<!--")) {
            normalized = normalized.substring(4).trim();
        }
        if (normalized.endsWith("-->")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        return normalized;
    }

    private String normalizeExtractedTitle(String value) {
        String title = HtmlUtils.htmlUnescape(value == null ? "" : value)
                .replaceAll("\\s+", " ")
                .trim();
        return title.length() > 200 ? title.substring(0, 200).trim() : title;
    }

    private byte[] readLimited(InputStream inputStream, int maxBytes) throws IOException {
        try (inputStream) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            byte[] buffer = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    return outputStream.toByteArray();
                }
                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
            return outputStream.toByteArray();
        }
    }

    private URI validate(String rawUrl) {
        return ExternalUrlValidator.validate(rawUrl, policy());
    }

    private URI validate(URI uri) {
        return ExternalUrlValidator.validate(uri, policy());
    }

    private URI validateRedirect(URI currentUri, String location) {
        return ExternalUrlValidator.validateRedirect(currentUri, location, policy());
    }

    private ExternalUrlValidator.Policy policy() {
        NasProperties.Bookmarks bookmarks = nasProperties.getBookmarks();
        return new ExternalUrlValidator.Policy(
                "Bookmark metadata URL",
                "bookmark metadata fetches",
                "Bookmark metadata port",
                "Bookmark metadata host",
                "Bookmark metadata server",
                bookmarks.getAllowedPorts(),
                bookmarks.isBlockPrivateNetworks()
        );
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean isHtmlContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return normalized.equals("text/html") || normalized.equals("application/xhtml+xml");
    }

    private boolean isManifestContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return normalized.equals("application/manifest+json")
                || normalized.equals("application/json")
                || normalized.equals("text/json")
                || normalized.equals("text/plain");
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private Map<String, String> allowedFaviconContentTypes() {
        return Map.of(
                "image/png", "png",
                "image/jpeg", "jpg",
                "image/gif", "gif",
                "image/webp", "webp",
                "image/x-icon", "ico",
                "image/vnd.microsoft.icon", "ico"
        );
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
            // Ignore close failure while handling an HTTP response.
        }
    }

    private record HtmlResponse(URI finalUri, String html) {
    }
}
