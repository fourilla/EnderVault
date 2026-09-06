package io.github.fourilla.endervault.web.api.v1.upload;

import io.github.fourilla.endervault.upload.DirectoryUpload;
import io.github.fourilla.endervault.upload.DirectoryUploadManifest;
import io.github.fourilla.endervault.upload.DirectoryUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadAdmissionRequest;
import io.github.fourilla.endervault.upload.ResumableUploadAdmissionResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import io.github.fourilla.endervault.web.support.ActionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/files/directory-uploads")
public class DirectoryUploadApiController {
    private final DirectoryUploadService service;
    public DirectoryUploadApiController(DirectoryUploadService service) { this.service = service; }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ActionResponse> rejected(ResponseStatusException exception) {
        String reason = exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(ActionResponse.error(
                reason == null ? "Directory upload request was rejected." : reason));
    }

    @PostMapping
    public Response create(@RequestParam(value = "path", required = false) String path,
            @RequestBody DirectoryUploadManifest manifest) throws IOException { return Response.from(service.create(path, manifest)); }
    @GetMapping("/{id}")
    public Response get(@PathVariable String id) throws IOException { return Response.from(service.get(id)); }
    @PostMapping("/{id}/complete")
    public Response complete(@PathVariable String id) throws IOException { return Response.from(service.complete(id)); }
    @DeleteMapping("/{id}")
    public Response cancel(@PathVariable String id) throws IOException { return Response.from(service.cancel(id)); }
    @PostMapping("/{id}/files")
    public ResumableUploadAdmissionResponse admit(@PathVariable String id, @RequestParam String relativePath,
            @RequestBody ResumableUploadAdmissionRequest request) throws IOException { return service.admit(id, relativePath, request); }

    public record Response(boolean ok, String id, DirectoryUpload.Status status, Instant expiresAt,
            List<String> completedPaths, String pendingDecisionId, String committedPath) {
        static Response from(DirectoryUpload upload) {
            return new Response(true, upload.id(), upload.status(), upload.expiresAt(),
                    upload.files().stream().filter(DirectoryUpload.Entry::received).map(DirectoryUpload.Entry::path).toList(),
                    upload.pendingDecisionId(), upload.committedPath());
        }
    }
}
