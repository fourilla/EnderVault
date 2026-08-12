package io.github.fourilla.endervault.remote;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import org.springframework.http.ContentDisposition;

final class RemoteHttpResponse implements AutoCloseable {

    private final URI uri;
    private final HttpResponse<InputStream> response;

    RemoteHttpResponse(URI uri, HttpResponse<InputStream> response) {
        this.uri = uri;
        this.response = response;
    }

    URI uri() {
        return uri;
    }

    int status() {
        return response.statusCode();
    }

    InputStream body() {
        return response.body();
    }

    long contentLength() {
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    String contentDispositionFileName() {
        return response.headers().firstValue("Content-Disposition")
                .map(this::parseContentDispositionFileName)
                .orElse(null);
    }

    String contentType() {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    String contentRangeHeader() {
        return response.headers().firstValue("Content-Range").orElse("");
    }

    RemoteContentRange contentRange() {
        return RemoteContentRange.parse(contentRangeHeader());
    }

    String strongEtag() {
        String etag = response.headers().firstValue("ETag").orElse("").trim();
        return etag.regionMatches(true, 0, "W/", 0, 2) ? "" : etag;
    }

    String lastModified() {
        return response.headers().firstValue("Last-Modified").orElse("").trim();
    }

    String retryAfter() {
        return response.headers().firstValue("Retry-After").orElse("").trim();
    }

    private String parseContentDispositionFileName(String value) {
        try {
            return ContentDisposition.parse(value).getFilename();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // Closing a remote response is best effort.
        }
    }
}
