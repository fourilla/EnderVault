package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailFile;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FileResponseService;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import io.github.fourilla.endervault.web.support.SelectedItems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminFileTransferController {

    private final StorageService storageService;
    private final FileResponseService fileResponseService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final ActivityLogService activityLogService;

    public AdminFileTransferController(
            StorageService storageService,
            FileResponseService fileResponseService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/download")
    public ResponseEntity<?> download(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            HttpServletRequest request
    ) throws IOException {
        FileItem fileItem = storageService.describeVaultChild(path, item);
        recentService.recordVaultPath(fileItem.path());
        activityLogService.record("DOWNLOAD", request, fileItem.path(), null, "Downloaded " + fileItem.name());
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/files/detail/download")
    public ResponseEntity<?> downloadFromDetail(
            @RequestParam("path") String path,
            HttpServletRequest request
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        activityLogService.record("DOWNLOAD", request, detail.path(), null, "Downloaded " + detail.name());
        Path file = storageService.resolveVaultFile(path);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/files/thumbnail")
    public ResponseEntity<Resource> thumbnail(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        String vaultPath = storageService.describeVaultChild(path, item).path();
        return thumbnailResponse(file, vaultPath);
    }

    @GetMapping("/files/detail/thumbnail")
    public ResponseEntity<Resource> thumbnailFromDetail(@RequestParam("path") String path) throws IOException {
        Path file = storageService.resolveVaultFile(path);
        return thumbnailResponse(file, path);
    }

    @GetMapping("/files/download.zip")
    public void downloadZip(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> items = SelectedItems.from(request);
        if (items.isEmpty()) {
            FlashNotifications.warning(redirectAttributes, "Select at least one item.");
            response.sendRedirect(ActionResponseSupport.redirectUrl(redirectToFiles(path, view, sort, direction, page, size)));
            return;
        }

        if (items.size() == 1) {
            FileItem item = storageService.describeVaultChild(path, items.get(0));
            if (!item.directory()) {
                recentService.recordVaultPath(item.path());
                activityLogService.record("DOWNLOAD", request, item.path(), null, "Downloaded " + item.name());
                Path file = storageService.resolveFile(StorageScope.VAULT, path, item.name());
                writeAttachment(file, response);
                return;
            }
        }

        activityLogService.record("DOWNLOAD_ZIP", request, path, null, "Downloaded ZIP with " + items.size() + " item(s)");
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, zipContentDisposition(items));
        storageService.writeZip(StorageScope.VAULT, path, items, response.getOutputStream());
    }

    @GetMapping("/files/detail/download.zip")
    public void downloadZipFromDetail(
            @RequestParam("path") String path,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        if (!detail.directory()) {
            throw new NoSuchFileException(detail.path());
        }

        activityLogService.record("DOWNLOAD_ZIP", request, detail.path(), null, "Downloaded ZIP for " + detail.name());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"endervault.zip\"");
        storageService.writeZip(
                StorageScope.VAULT,
                detail.parentPath(),
                List.of(detail.name()),
                response.getOutputStream()
        );
    }

    @GetMapping("/files/preview")
    public ResponseEntity<?> preview(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        return fileResponseService.inline(file, headers);
    }

    @GetMapping("/files/detail/preview")
    public ResponseEntity<?> previewFromDetail(
            @RequestParam("path") String path,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        Path file = storageService.resolveVaultFile(path);
        recentService.recordVaultPath(path);
        return fileResponseService.inline(file, headers);
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }

    private String redirectToFiles(
            String path,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) {
        int pageNumber = page == null ? 1 : Math.max(1, page);

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String zipContentDisposition(List<String> items) {
        String filename = items.size() == 1 ? items.get(0) + ".zip" : "endervault.zip";
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private void writeAttachment(Path file, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(Files.size(file));
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                        .build()
                        .toString()
        );
        Files.copy(file, response.getOutputStream());
    }

    private ResponseEntity<Resource> thumbnailResponse(Path file, String vaultPath) throws IOException {
        if (!thumbnailService.supportsThumbnail(file)) {
            throw new NoSuchFileException(vaultPath);
        }

        ThumbnailFile thumbnail = thumbnailService.thumbnail(file, vaultPath);
        CacheControl cacheControl = thumbnail.generated()
                ? CacheControl.maxAge(Duration.ofDays(30)).cachePublic()
                : CacheControl.noStore();
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.parseMediaType(thumbnail.mediaType()))
                .contentLength(Files.size(thumbnail.path()))
                .body(new FileSystemResource(thumbnail.path()));
    }
}
