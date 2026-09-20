package io.github.fourilla.endervault.web.api.v1.upload;

import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Advisory only: admission and publication retain their own validation. */
@RestController
public class AdminUploadPreflightApiController {
    private final StorageService storage;

    public AdminUploadPreflightApiController(StorageService storage) { this.storage = storage; }

    @PostMapping("/api/v1/files/upload-preflight")
    public Response inspect(@RequestBody Request request) throws IOException {
        if (request.names() == null || request.names().isEmpty() || request.names().size() > 200) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Provide between 1 and 200 upload names per check.");
        }
        String path = storage.normalizeVaultDirectory(request.path());
        var conflicts = new ArrayList<String>();
        for (String name : request.names().stream().distinct().toList()) {
            storage.validateVaultEntryName(name);
            try {
                storage.describeVaultChild(path, name);
                conflicts.add(name);
            } catch (NoSuchFileException ignored) {
                // The final commit must still check for items created after this inspection.
            }
        }
        return new Response(true, List.copyOf(conflicts));
    }

    public record Request(String path, List<String> names) {}
    public record Response(boolean ok, List<String> conflicts) {}
}
