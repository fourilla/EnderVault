package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.web.support.ActionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SharedBrowserApiController {

    private final SharedBrowserQueryService queryService;

    public SharedBrowserApiController(SharedBrowserQueryService queryService) {
        this.queryService = queryService;
    }

    // Successful JSON view queries own SHARE_ACCESS; the HTML host and byte endpoints do not duplicate it.
    @GetMapping(value = "/s/{token}/listing.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SharedBrowserPayloads.Listing> listing(
            @PathVariable String token,
            @RequestParam(required = false) String path,
            HttpServletRequest request
    ) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queryService.listing(token, path, request));
    }

    @GetMapping(value = "/s/{token}/detail.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SharedBrowserPayloads.Detail> detail(
            @PathVariable String token,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String item,
            HttpServletRequest request
    ) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queryService.detail(token, path, item, request));
    }

    @ExceptionHandler({IOException.class, StorageAccessException.class, IllegalArgumentException.class})
    public ResponseEntity<ActionResponse> queryError(Exception exception) {
        HttpStatus status;
        String message;
        if (exception instanceof NoSuchFileException) {
            status = HttpStatus.NOT_FOUND;
            message = "Shared content is unavailable.";
        } else if (exception instanceof StorageAccessException) {
            status = HttpStatus.FORBIDDEN;
            message = "Shared content is unavailable.";
        } else if (exception instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            message = "Invalid shared content request.";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Shared content could not be loaded.";
        }
        // Filesystem exception messages can contain absolute paths outside the public share scope.
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ActionResponse.error(message));
    }
}
