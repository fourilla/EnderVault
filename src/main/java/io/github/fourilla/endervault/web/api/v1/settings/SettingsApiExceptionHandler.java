package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.web.support.ActionResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.github.fourilla.endervault.web.api.v1.settings")
public class SettingsApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ActionResponse> invalidSettings(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ActionResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ActionResponse> saveFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ActionResponse.error("Settings could not be saved."));
    }
}
