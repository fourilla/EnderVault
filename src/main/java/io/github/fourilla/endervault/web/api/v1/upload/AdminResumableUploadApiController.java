package io.github.fourilla.endervault.web.api.v1.upload;

import io.github.fourilla.endervault.upload.ResumableUploadAdmissionRequest;
import io.github.fourilla.endervault.upload.ResumableUploadAdmissionResponse;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminResumableUploadApiController {

    private final ResumableUploadService uploadService;

    public AdminResumableUploadApiController(ResumableUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(
            value = "/api/v1/files/upload-sessions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResumableUploadAdmissionResponse admit(
            @RequestParam(value = "path", required = false) String path,
            @RequestBody ResumableUploadAdmissionRequest request
    ) throws IOException {
        ResumableUploadSession session = uploadService.admitAdmin(
                path,
                request.filename(),
                request.contentType(),
                request.size(),
                request.fingerprint(),
                request.resumeSessionId()
        );
        return ResumableUploadAdmissionResponse.from(session, uploadService);
    }
}
