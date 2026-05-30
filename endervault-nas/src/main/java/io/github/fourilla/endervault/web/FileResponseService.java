package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class FileResponseService {

    private final StorageService storageService;

    public FileResponseService(StorageService storageService) {
        this.storageService = storageService;
    }

    public ResponseEntity<Resource> attachment(Path file) throws IOException {
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("attachment", file))
                .body(resource);
    }

    public ResponseEntity<?> inline(Path file, HttpHeaders requestHeaders) throws IOException {
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = MediaType.parseMediaType(storageService.mediaType(file));
        List<HttpRange> ranges = requestHeaders.getRange();

        if (!ranges.isEmpty()) {
            ResourceRegion region = ranges.get(0).toResourceRegion(resource);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("inline", file))
                    .body(region);
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("inline", file))
                .body(resource);
    }

    private String contentDisposition(String type, Path file) {
        ContentDisposition disposition = ContentDisposition.builder(type)
                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                .build();
        return disposition.toString();
    }
}

