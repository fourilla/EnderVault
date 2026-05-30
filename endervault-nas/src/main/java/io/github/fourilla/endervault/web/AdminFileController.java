package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.FileSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailFile;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileController {

    private static final int FALLBACK_PAGE_SIZE = 200;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final FileResponseService fileResponseService;
    private final ShareLinkService shareLinkService;
    private final ThumbnailService thumbnailService;

    public AdminFileController(
            NasProperties nasProperties,
            StorageService storageService,
            FileResponseService fileResponseService,
            ShareLinkService shareLinkService,
            ThumbnailService thumbnailService
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
        this.shareLinkService = shareLinkService;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/files")
    public String files(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) throws IOException {
        String normalizedView = normalizeView(view);
        FileSort fileSort = normalizeSort(sort);
        SortDirection sortDirection = normalizeDirection(direction);
        int pageSize = normalizePageSize(size);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path, fileSort, sortDirection);
        FilePage filePage = pageFiles(listing.files(), page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("view", normalizedView);
        model.addAttribute("nextView", nextView(normalizedView));
        model.addAttribute("viewToggleLabel", viewToggleLabel(normalizedView));
        model.addAttribute("viewToggleIcon", viewToggleIcon(normalizedView));
        model.addAttribute("sort", fileSort.parameter());
        model.addAttribute("dir", sortDirection.parameter());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("filePage", filePage);
        return "files";
    }

    @GetMapping("/files/detail")
    public String detail(@RequestParam("path") String path, Model model) throws IOException {
        FileDetail detail = detailForPath(path);
        model.addAttribute("detail", detail);
        model.addAttribute("shares", shareLinkService.listForVaultPath(detail.path()));
        model.addAttribute("shareBaseUrl", shareBaseUrl());
        return "file-detail";
    }

    @PostMapping("/files/upload")
    public String upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        for (MultipartFile file : files) {
            storageService.upload(path, file);
        }
        redirectAttributes.addFlashAttribute("message", "Upload complete.");
        return redirectToFiles(path, view, sort, direction, page, size);
    }

    @PostMapping("/files/folders")
    public String createFolder(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "size", required = false) Integer size,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.createDirectory(path, name);
        redirectAttributes.addFlashAttribute("message", "Folder created.");
        return redirectToFiles(path, view, sort, direction, 1, size);
    }

    @PostMapping("/files/rename")
    public String rename(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("newName") String newName,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileItem oldItem = storageService.describeVaultChild(path, item);
        storageService.rename(path, item, newName);
        FileItem newItem = storageService.describeVaultChild(path, newName);
        shareLinkService.moveVaultPath(oldItem.path(), newItem.path());
        redirectAttributes.addFlashAttribute("message", "Item renamed.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/detail/rename")
    public String renameFromDetail(
            @RequestParam("path") String path,
            @RequestParam("newName") String newName,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        String newPath = storageService.renameVaultPath(detail.path(), newName);
        shareLinkService.moveVaultPath(detail.path(), newPath);
        redirectAttributes.addFlashAttribute("message", "Item renamed.");
        return redirectToDetail(newPath);
    }

    @PostMapping("/files/move")
    public String move(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("targetPath") String targetPath,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileItem oldItem = storageService.describeVaultChild(path, item);
        storageService.move(path, item, targetPath);
        FileItem newItem = storageService.describeVaultChild(targetPath, item);
        shareLinkService.moveVaultPath(oldItem.path(), newItem.path());
        redirectAttributes.addFlashAttribute("message", "Item moved.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/detail/move")
    public String moveFromDetail(
            @RequestParam("path") String path,
            @RequestParam("targetPath") String targetPath,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        String newPath = storageService.moveVaultPath(detail.path(), targetPath);
        shareLinkService.moveVaultPath(detail.path(), newPath);
        redirectAttributes.addFlashAttribute("message", "Item moved.");
        return redirectToDetail(newPath);
    }

    @PostMapping("/files/delete")
    public String delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "items", required = false) List<String> items,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (items == null || items.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "Select at least one item.");
            return redirectToFiles(path, view, sort, direction, page, size);
        }
        List<FileItem> deletedItems = new ArrayList<>();
        for (String item : items) {
            deletedItems.add(storageService.describeVaultChild(path, item));
        }
        storageService.delete(path, items);
        for (FileItem item : deletedItems) {
            shareLinkService.revokeVaultPath(item.path());
        }
        redirectAttributes.addFlashAttribute("message", "Selected items deleted.");
        return redirectToFiles(path, view, sort, direction, page, size);
    }

    @PostMapping("/files/detail/delete")
    public String deleteFromDetail(
            @RequestParam("path") String path,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        storageService.deleteVaultPath(detail.path());
        shareLinkService.revokeVaultPath(detail.path());
        redirectAttributes.addFlashAttribute("message", "Item deleted.");
        return redirectToFiles(detail.parentPath());
    }

    @PostMapping("/files/share")
    public String share(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam(value = "expiresInDays", required = false) Integer expiresInDays,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        ShareLink shareLink = shareLinkService.create(path, item, expiresAt(expiresInDays));

        redirectAttributes.addFlashAttribute("message", "Share link created.");
        redirectAttributes.addFlashAttribute("shareUrl", shareUrl(shareLink));
        return redirectToFiles(path);
    }

    @PostMapping("/files/detail/share")
    public String shareFromDetail(
            @RequestParam("path") String path,
            @RequestParam(value = "expiresInDays", required = false) Integer expiresInDays,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        ShareLink shareLink = shareLinkService.createForVaultPath(detail.path(), expiresAt(expiresInDays));
        redirectAttributes.addFlashAttribute("message", "Share link created.");
        redirectAttributes.addFlashAttribute("shareUrl", shareUrl(shareLink));
        return redirectToDetail(detail.path());
    }

    @PostMapping("/files/detail/shares/revoke")
    public String revokeShareFromDetail(
            @RequestParam("path") String path,
            @RequestParam("token") String token,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        shareLinkService.revoke(token);
        redirectAttributes.addFlashAttribute("message", "Share link revoked.");
        return redirectToDetail(detail.path());
    }

    @PostMapping("/files/detail/shares/delete")
    public String deleteShareFromDetail(
            @RequestParam("path") String path,
            @RequestParam("token") String token,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        shareLinkService.delete(token);
        redirectAttributes.addFlashAttribute("message", "Share link deleted.");
        return redirectToDetail(detail.path());
    }

    @GetMapping("/files/download")
    public ResponseEntity<?> download(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/files/detail/download")
    public ResponseEntity<?> downloadFromDetail(@RequestParam("path") String path) throws IOException {
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
    public Object downloadZip(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "items", required = false) List<String> items,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (items == null || items.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "Select at least one item.");
            return redirectToFiles(path, view, sort, direction, page, size);
        }

        if (items.size() == 1) {
            FileItem item = storageService.describeVaultChild(path, items.get(0));
            if (!item.directory()) {
                Path file = storageService.resolveFile(StorageScope.VAULT, path, item.name());
                return fileResponseService.attachment(file);
            }
        }

        StreamingResponseBody body = outputStream ->
                storageService.writeZip(StorageScope.VAULT, path, items, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, zipContentDisposition(items))
                .body(body);
    }

    @GetMapping("/files/detail/download.zip")
    public void downloadZipFromDetail(
            @RequestParam("path") String path,
            HttpServletResponse response
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        if (!detail.directory()) {
            throw new NoSuchFileException(detail.path());
        }

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
        return fileResponseService.inline(file, headers);
    }

    private String redirectToFiles(String path) {
        return redirectToFiles(path, null);
    }

    private String redirectToFiles(String path, String view) {
        return redirectToFiles(path, view, null, null, null, null);
    }

    private String redirectToFiles(
            String path,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) {
        String normalizedView = normalizeView(view);
        FileSort fileSort = normalizeSort(sort);
        SortDirection sortDirection = normalizeDirection(direction);
        int pageSize = normalizePageSize(size);
        int pageNumber = page == null ? 1 : Math.max(1, page);

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (!normalizedView.equals(defaultView())) {
            builder.queryParam("view", normalizedView);
        }
        if (fileSort != defaultSort()) {
            builder.queryParam("sort", fileSort.parameter());
        }
        if (sortDirection != defaultDirection()) {
            builder.queryParam("dir", sortDirection.parameter());
        }
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        if (pageSize != defaultPageSize()) {
            builder.queryParam("size", pageSize);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }

    private String shareUrl(ShareLink shareLink) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/{token}")
                .buildAndExpand(shareLink.token())
                .toUriString();
    }

    private String shareBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString();
    }

    private String zipContentDisposition(List<String> items) {
        String filename = items.size() == 1 ? items.get(0) + ".zip" : "endervault.zip";
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private ResponseEntity<Resource> thumbnailResponse(Path file, String vaultPath) throws IOException {
        if (!storageService.mediaType(file).startsWith("video/")) {
            throw new NoSuchFileException(vaultPath);
        }

        ThumbnailFile thumbnail = thumbnailService.videoThumbnail(file, vaultPath);
        CacheControl cacheControl = thumbnail.generated()
                ? CacheControl.maxAge(Duration.ofDays(30)).cachePublic()
                : CacheControl.noStore();
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.parseMediaType(thumbnail.mediaType()))
                .contentLength(Files.size(thumbnail.path()))
                .body(new FileSystemResource(thumbnail.path()));
    }

    private Instant expiresAt(Integer expiresInDays) {
        if (expiresInDays == null || expiresInDays <= 0) {
            return null;
        }
        return Instant.now().plus(Duration.ofDays(expiresInDays));
    }

    private String normalizeView(String view) {
        if ("grid".equalsIgnoreCase(view)) {
            return "grid";
        }
        if ("table".equalsIgnoreCase(view)) {
            return "table";
        }
        return defaultView();
    }

    private String defaultView() {
        return "grid".equalsIgnoreCase(nasProperties.getBrowser().getDefaultView()) ? "grid" : "table";
    }

    private String nextView(String view) {
        return "grid".equals(view) ? "table" : "grid";
    }

    private String viewToggleLabel(String view) {
        return "grid".equals(view) ? "Switch to table view" : "Switch to grid view";
    }

    private String viewToggleIcon(String view) {
        return "grid".equals(view) ? "fas fa-bars" : "fas fa-border-all";
    }

    private FileSort normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort();
        }
        return FileSort.from(sort);
    }

    private FileSort defaultSort() {
        return FileSort.from(nasProperties.getBrowser().getDefaultSort());
    }

    private SortDirection normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return defaultDirection();
        }
        return SortDirection.from(direction);
    }

    private SortDirection defaultDirection() {
        return SortDirection.from(nasProperties.getBrowser().getDefaultDirection());
    }

    private int normalizePageSize(Integer size) {
        if (size == null) {
            return defaultPageSize();
        }
        return pageSizeOptions().contains(size) ? size : defaultPageSize();
    }

    private int defaultPageSize() {
        int configuredPageSize = nasProperties.getBrowser().getDefaultPageSize();
        return configuredPageSize > 0 ? configuredPageSize : FALLBACK_PAGE_SIZE;
    }

    private List<Integer> pageSizeOptions() {
        List<Integer> options = new ArrayList<>(PAGE_SIZE_OPTIONS);
        int defaultPageSize = defaultPageSize();
        if (!options.contains(defaultPageSize)) {
            options.add(defaultPageSize);
            options.sort(Integer::compareTo);
        }
        return List.copyOf(options);
    }

    private FilePage pageFiles(List<FileItem> files, Integer requestedPage, int pageSize) {
        int totalItems = files.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int page = requestedPage == null ? 1 : requestedPage;
        page = Math.max(1, Math.min(page, totalPages));
        int startIndex = totalItems == 0 ? 0 : (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<FileItem> items = totalItems == 0 ? List.of() : files.subList(startIndex, endIndex);
        int startItem = totalItems == 0 ? 0 : startIndex + 1;
        return new FilePage(items, page, pageSize, totalItems, totalPages, startItem, endIndex);
    }
}
