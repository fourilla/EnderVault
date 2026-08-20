package io.github.fourilla.endervault.upload;

import org.springframework.http.HttpStatus;

public class ResumableUploadRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final Integer retryAfterSeconds;

    public ResumableUploadRejectedException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ResumableUploadRejectedException(HttpStatus status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
