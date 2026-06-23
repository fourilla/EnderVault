package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;

class FileResponseServiceTest {

    @TempDir
    Path root;

    private FileResponseService fileResponseService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        StorageService storageService = new StorageService(properties);
        storageService.initialize();
        fileResponseService = new FileResponseService(storageService);
    }

    @Test
    void inlineStreamsRequestedByteRange() throws Exception {
        Path file = root.resolve("sample.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.set(HttpHeaders.RANGE, "bytes=2-5");

        ResponseEntity<?> response = fileResponseService.inline(file, requestHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4L);

        Resource resource = (Resource) response.getBody();
        assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("2345");
    }

    @Test
    void inlineAddsUtf8CharsetForTextPreview() throws Exception {
        Path file = root.resolve("note.txt");
        Files.writeString(file, "한글", StandardCharsets.UTF_8);

        ResponseEntity<?> response = fileResponseService.inline(file, new HttpHeaders());

        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void inlineServesActiveContentAsPlainTextWithNosniff() throws Exception {
        Path file = root.resolve("page.html");
        Files.writeString(file, "<script>alert(1)</script>", StandardCharsets.UTF_8);

        ResponseEntity<?> response = fileResponseService.inline(file, new HttpHeaders());

        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void attachmentIncludesNosniffAndFilename() throws Exception {
        Path file = root.resolve("report.txt");
        Files.writeString(file, "report", StandardCharsets.UTF_8);

        ResponseEntity<Resource> response = fileResponseService.attachment(file);

        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("report.txt");
    }

    @Test
    void inlineRejectsUnsatisfiableByteRange() throws Exception {
        Path file = root.resolve("sample.mp4");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.set(HttpHeaders.RANGE, "bytes=20-30");

        ResponseEntity<?> response = fileResponseService.inline(file, requestHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
    }
}
