package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.settings.FileRequestSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/file-requests")
public class FileRequestSettingsApiController {

    private final FileRequestSettingsService fileRequestSettingsService;

    public FileRequestSettingsApiController(FileRequestSettingsService fileRequestSettingsService) {
        this.fileRequestSettingsService = fileRequestSettingsService;
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        fileRequestSettingsService.save(fileRequestSettingsService.updateFrom(parameters));
        return ActionResponse.ok(FlashNotification.success("File request settings saved and applied."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ActionResponse> invalidSettings(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ActionResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ActionResponse> saveFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ActionResponse.error("File request settings could not be saved."));
    }
}
