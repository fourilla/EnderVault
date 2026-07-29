package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.common.ExternalUrlValidator;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class BookmarkMetadataFetcher {

    private final NasProperties nasProperties;
    private final OutboundHttpClientRegistry httpClientRegistry;
    private final BookmarkMetadataParser parser = new BookmarkMetadataParser();

    public BookmarkMetadataFetcher(NasProperties nasProperties, OutboundHttpClientRegistry httpClientRegistry) {
        this.nasProperties = nasProperties;
        this.httpClientRegistry = httpClientRegistry;
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
        String title = parser.extractTitle(htmlResponse.html());
        if (title.isBlank()) {
            title = fetchManifestTitle(htmlResponse.finalUri(), htmlResponse.html()).orElse("");
        }
        BookmarkMetadataFetchResult.Favicon favicon = fetchFirstFavicon(htmlResponse.finalUri(), htmlResponse.html())
                .orElse(null);
        return new BookmarkMetadataFetchResult(title, favicon);
    }

    private HttpClient httpClient() {
        return httpClientRegistry.client(
                NetworkRoute.DIRECT,
                Duration.ofSeconds(nasProperties.getBookmarks().getConnectTimeoutSeconds())
        );
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
        for (URI candidate : parser.faviconCandidates(pageUri, html)) {
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
        for (URI candidate : parser.manifestCandidates(pageUri, html)) {
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
            return parser.titleFromManifest(manifest);
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
