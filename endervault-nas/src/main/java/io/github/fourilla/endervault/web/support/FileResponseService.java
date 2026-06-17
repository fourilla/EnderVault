package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class FileResponseService {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String NOSNIFF = "nosniff";
    private static final Set<MediaType> ACTIVE_INLINE_TYPES = Set.of(
            MediaType.TEXT_HTML,
            MediaType.TEXT_XML,
            MediaType.APPLICATION_XML,
            MediaType.APPLICATION_XHTML_XML,
            MediaType.parseMediaType("image/svg+xml")
    );

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
                .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
                .body(resource);
    }

    public ResponseEntity<?> inline(Path file, HttpHeaders requestHeaders) throws IOException {
        Resource resource = new FileSystemResource(file);
        MediaType mediaType = inlineMediaType(file);
        long fileSize = Files.size(file);
        List<HttpRange> ranges = requestHeaders.getRange();

        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(fileSize);
            long end = range.getRangeEnd(fileSize);
            if (start >= fileSize || start > end) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                        .build();
            }

            long contentLength = end - start + 1;
            Resource rangeResource = new RangeResource(file, start, contentLength);

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .contentLength(contentLength)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(start, end, fileSize))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("inline", file))
                    .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
                    .body(rangeResource);
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("inline", file))
                .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
                .body(resource);
    }

    private String contentDisposition(String type, Path file) {
        ContentDisposition disposition = ContentDisposition.builder(type)
                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                .build();
        return disposition.toString();
    }

    private MediaType inlineMediaType(Path file) throws IOException {
        MediaType mediaType = MediaType.parseMediaType(storageService.mediaType(file));
        if (unsafeInlineMediaType(mediaType)) {
            return new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
        }
        if ("text".equalsIgnoreCase(mediaType.getType()) && mediaType.getCharset() == null) {
            return new MediaType(mediaType, StandardCharsets.UTF_8);
        }
        return mediaType;
    }

    private boolean unsafeInlineMediaType(MediaType mediaType) {
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return ACTIVE_INLINE_TYPES.stream().anyMatch(unsafeType -> unsafeType.includes(mediaType))
                || "xml".equals(subtype)
                || subtype.endsWith("+xml");
    }

    private static class RangeResource extends AbstractResource {
        private final Path file;
        private final long start;
        private final long length;

        RangeResource(Path file, long start, long length) {
            this.file = file;
            this.start = start;
            this.length = length;
        }

        @Override
        public String getDescription() {
            return "Byte range resource for " + file;
        }

        @Override
        public String getFilename() {
            return file.getFileName().toString();
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream inputStream = Files.newInputStream(file);
            boolean success = false;
            try {
                inputStream.skipNBytes(start);
                success = true;
                return new BoundedInputStream(inputStream, length);
            } finally {
                if (!success) {
                    inputStream.close();
                }
            }
        }
    }

    private static class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int bytesRead = delegate.read(bytes, offset, (int) Math.min(length, remaining));
            if (bytesRead != -1) {
                remaining -= bytesRead;
            }
            return bytesRead;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
