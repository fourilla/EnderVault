package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.filetool.archive.ArchiveManifest;
import io.github.fourilla.endervault.filetool.archive.ArchiveService;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fs/archive")
public class ArchiveEntriesApiController {

    private final StorageService storageService;
    private final ArchiveService archiveService;

    public ArchiveEntriesApiController(StorageService storageService, ArchiveService archiveService) {
        this.storageService = storageService;
        this.archiveService = archiveService;
    }

    @GetMapping(value = "/entries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArchiveEntriesPayload entries(
            @RequestParam("path") String path,
            @RequestParam(value = "parent", required = false) String parent
    ) throws IOException {
        Path archive = storageService.resolveVaultFile(path);
        ArchiveManifest manifest = archiveService.manifest(archive);
        return ArchiveEntriesPayload.from(manifest, parent);
    }
}
