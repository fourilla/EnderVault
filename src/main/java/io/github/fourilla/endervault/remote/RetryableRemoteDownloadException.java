package io.github.fourilla.endervault.remote;

import java.io.IOException;

final class RetryableRemoteDownloadException extends IOException {

    RetryableRemoteDownloadException(String message) {
        super(message);
    }

    RetryableRemoteDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
