package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.ByteSizeFormatter;

public record RemoteDownloadProbe(
        String sourceUrl,
        String finalUrl,
        String targetDirectory,
        String fileName,
        String targetPath,
        String contentType,
        long contentLength
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

    public boolean htmlLike() {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    public String targetDirectoryLabel() {
        return targetDirectory == null || targetDirectory.isBlank() ? "/" : targetDirectory;
    }

    public String warningLabel() {
        return htmlLike()
                ? "The remote server reports HTML content. This may save a web page, not the media/file you expected."
                : "";
    }
}
