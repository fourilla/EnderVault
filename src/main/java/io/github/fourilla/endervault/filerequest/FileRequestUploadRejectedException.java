package io.github.fourilla.endervault.filerequest;

import org.springframework.http.HttpStatus;

public class FileRequestUploadRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final Integer retryAfterSeconds;

    public FileRequestUploadRejectedException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public FileRequestUploadRejectedException(HttpStatus status, String message, Integer retryAfterSeconds) {
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
