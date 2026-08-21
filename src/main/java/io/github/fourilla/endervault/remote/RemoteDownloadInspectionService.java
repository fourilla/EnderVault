package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.net.URI;
import org.springframework.stereotype.Service;

@Service
public class RemoteDownloadInspectionService {

    private final NasProperties nasProperties;
    private final RemoteDownloadHttpClient httpClient;
    private final RemoteDownloadFileNameResolver fileNameResolver;
    private final RemoteDownloadRetryPolicy retryPolicy;

    public RemoteDownloadInspectionService(
            NasProperties nasProperties,
            RemoteDownloadHttpClient httpClient,
            RemoteDownloadFileNameResolver fileNameResolver,
            RemoteDownloadRetryPolicy retryPolicy
    ) {
        this.nasProperties = nasProperties;
        this.httpClient = httpClient;
        this.fileNameResolver = fileNameResolver;
        this.retryPolicy = retryPolicy;
    }

    RemoteDownloadProbe inspect(RemoteDownloadRequestSpec requestSpec) throws InterruptedException {
        ProbeAttempt rangeAttempt = request(requestSpec, new RemoteByteRange(0L, 0L, ""));
        if (rangeAttempt.response() != null) {
            try (RemoteHttpResponse response = rangeAttempt.response()) {
                if (response.status() == 206) {
                    RemoteContentRange contentRange = response.contentRange();
                    if (contentRange != null && contentRange.start() == 0L && contentRange.end() == 0L) {
                        enforceMaxSize(contentRange.total());
                        return probe(
                                requestSpec,
                                response,
                                contentRange.total(),
                                RemoteDownloadProbeStatus.VERIFIED,
                                RemoteDownloadRangeCapability.SUPPORTED,
                                "The server returned a valid one-byte range."
                        );
                    }
                } else if (response.status() >= 200 && response.status() < 300) {
                    enforceMaxSize(response.contentLength());
                    return probe(
                            requestSpec,
                            response,
                            response.contentLength(),
                            RemoteDownloadProbeStatus.LIMITED,
                            RemoteDownloadRangeCapability.UNSUPPORTED,
                            "The server ignored the Range request and returned a normal response."
                    );
                } else if (response.status() == 416
                        && RemoteContentRange.unsatisfiedTotal(response.contentRangeHeader()) == 0L) {
                    return probe(
                            requestSpec,
                            response,
                            0L,
                            RemoteDownloadProbeStatus.VERIFIED,
                            RemoteDownloadRangeCapability.UNSUPPORTED,
                            "The remote resource is empty."
                    );
                }
            }
        }

        ProbeAttempt normalAttempt = request(requestSpec, null);
        if (normalAttempt.response() != null) {
            try (RemoteHttpResponse response = normalAttempt.response()) {
                if (response.status() >= 200 && response.status() < 300) {
                    enforceMaxSize(response.contentLength());
                    return probe(
                            requestSpec,
                            response,
                            response.contentLength(),
                            RemoteDownloadProbeStatus.LIMITED,
                            RemoteDownloadRangeCapability.UNSUPPORTED,
                            "The server accepted a normal GET request but not a byte range."
                    );
                }
                return unavailable(requestSpec, response.uri(), "Remote server returned HTTP " + response.status() + ".");
            }
        }

        String detail = normalAttempt.failureDetail();
        if (detail.isBlank()) {
            detail = rangeAttempt.failureDetail();
        }
        return unavailable(requestSpec, requestSpec.sourceUri(), detail);
    }

    RemoteDownloadProbe skipped(RemoteDownloadRequestSpec requestSpec) {
        String fileName = fileNameResolver.fileName(requestSpec.sourceUri(), "");
        return new RemoteDownloadProbe(
                requestSpec.sourceLabel(),
                requestSpec.sourceLabel(),
                requestSpec.targetDirectory(),
                fileName,
                fileNameResolver.targetPath(requestSpec.targetDirectory(), fileName),
                "",
                -1L,
                requestSpec.networkRoute(),
                RemoteDownloadProbeStatus.SKIPPED,
                RemoteDownloadRangeCapability.UNKNOWN,
                requestSpec.requestedConnections(),
                true,
                requestSpec.headerCount(),
                requestSpec.cookieIncluded(),
                "No network request was sent during inspection. Metadata will be determined when the download starts."
        );
    }

    private ProbeAttempt request(RemoteDownloadRequestSpec requestSpec, RemoteByteRange range)
            throws InterruptedException {
        String failure = "";
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            try {
                RemoteHttpResponse response = httpClient.exchange(requestSpec, range);
                if (!retryPolicy.retryableStatus(response.status()) || attempt == retryPolicy.maxRetries()) {
                    return new ProbeAttempt(response, "");
                }
                failure = "Remote server returned HTTP " + response.status() + ".";
                response.close();
            } catch (IOException ex) {
                failure = "Remote inspection connection failed: " + ex.getClass().getSimpleName() + ".";
                if (attempt == retryPolicy.maxRetries()) {
                    return new ProbeAttempt(null, failure);
                }
            }
            retryPolicy.awaitRetry(attempt + 1, () -> {
                // Thread.sleep below remains interruptible; Inspect has no task cancellation state.
            });
        }
        return new ProbeAttempt(null, failure);
    }

    private RemoteDownloadProbe probe(
            RemoteDownloadRequestSpec requestSpec,
            RemoteHttpResponse response,
            long contentLength,
            RemoteDownloadProbeStatus status,
            RemoteDownloadRangeCapability rangeCapability,
            String detail
    ) {
        String fileName = fileNameResolver.fileName(response);
        return new RemoteDownloadProbe(
                requestSpec.sourceLabel(),
                RemoteDownloadRequestSpec.safeUriLabel(response.uri()),
                requestSpec.targetDirectory(),
                fileName,
                fileNameResolver.targetPath(requestSpec.targetDirectory(), fileName),
                response.contentType(),
                contentLength,
                requestSpec.networkRoute(),
                status,
                rangeCapability,
                requestSpec.requestedConnections(),
                requestSpec.inspectionSkipped(),
                requestSpec.headerCount(),
                requestSpec.cookieIncluded(),
                detail
        );
    }

    private RemoteDownloadProbe unavailable(
            RemoteDownloadRequestSpec requestSpec,
            URI finalUri,
            String detail
    ) {
        String fileName = fileNameResolver.fileName(finalUri, "");
        return new RemoteDownloadProbe(
                requestSpec.sourceLabel(),
                RemoteDownloadRequestSpec.safeUriLabel(finalUri),
                requestSpec.targetDirectory(),
                fileName,
                fileNameResolver.targetPath(requestSpec.targetDirectory(), fileName),
                "",
                -1L,
                requestSpec.networkRoute(),
                RemoteDownloadProbeStatus.UNAVAILABLE,
                RemoteDownloadRangeCapability.UNKNOWN,
                requestSpec.requestedConnections(),
                requestSpec.inspectionSkipped(),
                requestSpec.headerCount(),
                requestSpec.cookieIncluded(),
                detail == null || detail.isBlank() ? "Remote inspection failed." : detail
        );
    }

    private void enforceMaxSize(long bytes) {
        long maxFileSize = nasProperties.getRemoteDownload().getMaxFileSizeBytes();
        if (maxFileSize > 0L && bytes > maxFileSize) {
            throw new StorageAccessException("Remote file exceeds the configured size limit.");
        }
    }

    private record ProbeAttempt(RemoteHttpResponse response, String failureDetail) {
    }
}
