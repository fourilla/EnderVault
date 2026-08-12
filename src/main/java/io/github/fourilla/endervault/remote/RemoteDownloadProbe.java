package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.storage.ConflictPolicy;

public record RemoteDownloadProbe(
        String sourceUrl,
        String finalUrl,
        String targetDirectory,
        String fileName,
        String targetPath,
        String contentType,
        long contentLength,
        NetworkRoute networkRoute,
        RemoteDownloadProbeStatus status,
        RemoteDownloadRangeCapability rangeCapability,
        int requestedConnections,
        ConflictPolicy conflictPolicy,
        boolean inspectionSkipped,
        int customHeaderCount,
        boolean cookieIncluded,
        String detail
) {

    public boolean sizeKnown() {
        return contentLength >= 0L;
    }

    public String contentLengthLabel() {
        return sizeKnown() ? ByteSizeFormatter.humanSize(contentLength) : "Unknown";
    }

    public String contentTypeLabel() {
        return contentType == null || contentType.isBlank() ? "Unknown" : contentType;
    }

    public String networkRouteLabel() {
        return networkRoute.label();
    }

    public String statusLabel() {
        return status.label();
    }

    public String rangeCapabilityLabel() {
        return rangeCapability.label();
    }

    public String requestOptionsLabel() {
        String cookie = cookieIncluded ? ", cookie included" : "";
        String inspection = inspectionSkipped ? ", inspection skipped" : "";
        return requestedConnections + " connection(s), " + customHeaderCount + " custom header(s)"
                + cookie + inspection;
    }

    public String conflictPolicyLabel() {
        return switch (conflictPolicy) {
            case CANCEL -> "Cancel";
            case RENAME -> "Rename and continue";
            case OVERWRITE -> "Overwrite";
        };
    }

    public boolean startAllowed() {
        return requestedConnections == 1 || rangeCapability != RemoteDownloadRangeCapability.UNSUPPORTED;
    }

    public boolean htmlLike() {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    public String targetDirectoryLabel() {
        return targetDirectory == null || targetDirectory.isBlank() ? "/" : targetDirectory;
    }

    public String warningLabel() {
        if (!startAllowed()) {
            return "The remote server does not support Range downloads. Choose 1 connection and inspect again.";
        }
        if (inspectionSkipped) {
            return "Inspection was skipped. The file name, size, type, final URL, and Range support were not verified.";
        }
        if (status == RemoteDownloadProbeStatus.UNAVAILABLE) {
            return "The remote file could not be inspected reliably. Starting may still succeed, but the file details are unknown.";
        }
        if (requestedConnections > 1 && rangeCapability == RemoteDownloadRangeCapability.UNKNOWN) {
            return "Range support could not be verified. A parallel download will fail if the server does not return valid ranges.";
        }
        if (htmlLike()) {
            return "The remote server reports HTML content. This may save a web page, not the media/file you expected.";
        }
        return "";
    }
}
