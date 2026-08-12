package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadHttpClient {

    private static final String DEFAULT_USER_AGENT = "EnderVault RemoteDownload";

    private final NasProperties nasProperties;
    private final RemoteDownloadValidator validator;
    private final OutboundHttpClientRegistry httpClientRegistry;

    public RemoteDownloadHttpClient(
            NasProperties nasProperties,
            RemoteDownloadValidator validator,
            OutboundHttpClientRegistry httpClientRegistry
    ) {
        this.nasProperties = nasProperties;
        this.validator = validator;
        this.httpClientRegistry = httpClientRegistry;
    }

    RemoteHttpResponse exchange(RemoteDownloadRequestSpec requestSpec, RemoteByteRange range)
            throws IOException, InterruptedException {
        HttpClient httpClient = httpClientRegistry.client(
                requestSpec.networkRoute(),
                Duration.ofSeconds(nasProperties.getRemoteDownload().getConnectTimeoutSeconds())
        );
        URI current = validator.validate(requestSpec.sourceUri());
        Map<String, String> currentHeaders = new LinkedHashMap<>(requestSpec.headers());
        int maxRedirects = nasProperties.getRemoteDownload().getMaxRedirects();
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(nasProperties.getRemoteDownload().getResponseTimeoutSeconds()))
                    .header("Accept-Encoding", "identity");
            currentHeaders.forEach(requestBuilder::header);
            if (currentHeaders.keySet().stream().noneMatch("user-agent"::equalsIgnoreCase)) {
                requestBuilder.header("User-Agent", DEFAULT_USER_AGENT);
            }
            if (range != null) {
                requestBuilder.header("Range", range.headerValue());
                if (!range.ifRange().isBlank()) {
                    requestBuilder.header("If-Range", range.ifRange());
                }
            }
            HttpResponse<InputStream> response = httpClient.send(
                    requestBuilder.GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (!isRedirect(response.statusCode())) {
                return new RemoteHttpResponse(current, response);
            }

            try (RemoteHttpResponse redirect = new RemoteHttpResponse(current, response)) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new StorageAccessException(
                                "Remote server returned a redirect without Location."
                        ));
                URI next = validator.validateRedirect(current, location);
                currentHeaders = new LinkedHashMap<>(
                        requestSpec.headersForRedirect(currentHeaders, current, next)
                );
                current = next;
            }
        }
        throw new StorageAccessException("Remote download exceeded the redirect limit.");
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
