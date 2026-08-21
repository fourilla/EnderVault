package io.github.fourilla.endervault.web.api.v1.upload;

import io.github.fourilla.endervault.upload.ResumableUploadRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ResumableUploadExceptionHandler {

    @ExceptionHandler(ResumableUploadRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(ResumableUploadRejectedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(false, exception.getMessage(), exception.retryAfterSeconds()));
    }

    public record ErrorResponse(boolean ok, String message, Integer retryAfterSeconds) {
    }
}
