package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.filetool.comic.ComicArchiveService;
import io.github.fourilla.endervault.filetool.comic.ComicPageResource;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminComicController {

    private final StorageService storageService;
    private final ComicArchiveService comicArchiveService;

    public AdminComicController(
            StorageService storageService,
            ComicArchiveService comicArchiveService
    ) {
        this.storageService = storageService;
        this.comicArchiveService = comicArchiveService;
    }

    @GetMapping("/files/detail/comic/preview")
    public String preview(
            @RequestParam("path") String path,
            Model model
    ) throws IOException {
        FileDetail detail = storageService.detail(StorageScope.VAULT, path);
        if (!comicArchiveService.isComic(detail)) {
            throw new NoSuchFileException(path);
        }

        Path cbzFile = storageService.resolveVaultFile(detail.path());
        model.addAttribute("comicTitle", detail.name());
        model.addAttribute("comicPageUrlPrefix", comicPageUrlPrefix(detail.path()));
        model.addAttribute("detail", detail);
        model.addAttribute("comicManifest", comicArchiveService.manifest(cbzFile));
        return "comic-preview";
    }

    @GetMapping("/files/detail/comic/page")
    public ResponseEntity<Resource> page(
            @RequestParam("path") String path,
            @RequestParam("page") int page
    ) throws IOException {
        FileDetail detail = storageService.detail(StorageScope.VAULT, path);
        if (!comicArchiveService.isComic(detail)) {
            throw new NoSuchFileException(path);
        }

        Path cbzFile = storageService.resolveVaultFile(detail.path());
        ComicPageResource pageResource = comicArchiveService.openPage(cbzFile, page);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePrivate())
                .contentType(MediaType.parseMediaType(pageResource.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(pageResource.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString());

        if (pageResource.contentLength() >= 0) {
            builder.contentLength(pageResource.contentLength());
        }

        return builder.body(pageResource.resource());
    }

    private String comicPageUrlPrefix(String path) {
        String baseUrl = UriComponentsBuilder.fromPath("/files/detail/comic/page")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
        return baseUrl + "&page=";
    }
}
