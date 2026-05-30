package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.ComicArchiveManifest;
import io.github.fourilla.endervault.filetool.ComicArchiveService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.recent.RecentService;
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
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FileResponseService;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import io.github.fourilla.endervault.web.support.TextFileLoadResponse;
import io.github.fourilla.endervault.web.support.TextFilePayload;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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
    private static final int READ_ONLY_PAGE_SIZE = 50;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final FileResponseService fileResponseService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final FileToolService fileToolService;
    private final ComicArchiveService comicArchiveService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;

    public AdminFileController(
            NasProperties nasProperties,
            StorageService storageService,
            FileResponseService fileResponseService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            FileToolService fileToolService,
            ComicArchiveService comicArchiveService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            TrashService trashService,
            ActivityLogService activityLogService
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.fileToolService = fileToolService;
        this.comicArchiveService = comicArchiveService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
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
        recentService.recordVaultPath(listing.path());
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
        model.addAttribute("favoritePaths", favoriteService.favoritePaths());
        return "files";
    }

    @GetMapping("/files/search")
    public String search(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) throws IOException {
        int pageSize = normalizePageSize(size);
        String normalizedQuery = normalizeSearchQuery(query);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path);
        List<FileItem> results = normalizedQuery.isEmpty()
                ? List.of()
                : storageService.search(StorageScope.VAULT, listing.path(), normalizedQuery);
        FilePage resultPage = pageFiles(results, page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("query", normalizedQuery);
        model.addAttribute("searchPerformed", !normalizedQuery.isEmpty());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("resultPage", resultPage);
        return "search";
    }

    @GetMapping("/files/read-only")
    public String readOnly(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) throws IOException {
        FileSort fileSort = normalizeSort(sort);
        SortDirection sortDirection = normalizeDirection(direction);
        int pageSize = size == null ? READ_ONLY_PAGE_SIZE : normalizePageSize(size);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path, fileSort, sortDirection);
        FilePage filePage = pageFiles(listing.files(), page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("sort", fileSort.parameter());
        model.addAttribute("dir", sortDirection.parameter());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("filePage", filePage);
        return "read-only";
    }

    @GetMapping("/files/detail")
    public String detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            Model model
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        FileToolDescriptor fileTool = fileToolService.resolve(detail);
        model.addAttribute("detail", detail);
        model.addAttribute("fileTool", fileTool);
        if (fileTool.text()) {
            model.addAttribute("textContent", fileToolService.readText(detail, storageService.resolveVaultFile(detail.path())));
        }
        if (fileTool.comic()) {
            ComicArchiveManifest comicManifest = comicArchiveService.manifest(storageService.resolveVaultFile(detail.path()));
            int comicPageIndex = comicArchiveService.normalizePage(comicManifest, comicPage);
            int comicPageNumber = comicManifest.empty() ? 0 : comicPageIndex + 1;
            model.addAttribute("comicManifest", comicManifest);
            model.addAttribute("comicPageIndex", comicPageIndex);
            model.addAttribute("comicPageNumber", comicPageNumber);
            model.addAttribute("comicPreviousPageNumber", Math.max(1, comicPageNumber - 1));
            model.addAttribute("comicNextPageNumber", Math.min(comicManifest.pageCount(), comicPageNumber + 1));
        }
        model.addAttribute("shares", shareLinkService.listForVaultPath(detail.path()));
        model.addAttribute("shareBaseUrl", shareBaseUrl());
        model.addAttribute("favorite", favoriteService.isFavorite(detail.path()));
        return "file-detail";
    }

    @PostMapping("/files/detail/text")
    public Object saveTextFromDetail(
            @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        fileToolService.writeText(detail, file, content);
        recentService.recordVaultPath(detail.path());
        activityLogService.record("TEXT_SAVE", request, detail.path(), null, "Saved text file " + detail.name());
        FlashNotification notification = FlashNotification.success("Text file saved.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectToDetail(detail.path());
    }

    @GetMapping("/files/detail/text/load")
    public ResponseEntity<TextFileLoadResponse> loadTextFromDetail(@RequestParam("path") String path) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        TextFileContent content = fileToolService.loadText(detail, file);
        if (!content.loaded()) {
            throw new StorageAccessException(content.message());
        }
        TextFilePayload payload = TextFilePayload.from(content);
        recentService.recordVaultPath(detail.path());
        return ResponseEntity.ok(TextFileLoadResponse.ok(payload));
    }

    @PostMapping("/files/upload")
    public Object upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<UploadedFilePayload> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            FileItem uploadedFile = storageService.upload(path, file);
            if (uploadedFile != null) {
                uploadedFiles.add(UploadedFilePayload.from(uploadedFile));
                activityLogService.record(
                        "UPLOAD",
                        request,
                        uploadedFile.path(),
                        null,
                        "Uploaded " + uploadedFile.name(),
                        Map.of("size", uploadedFile.sizeLabel())
                );
            }
        }
        FlashNotification notification = FlashNotification.success("Upload complete.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification, uploadedFiles));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectToFiles(path, view, sort, direction, page, size);
    }

    @PostMapping("/files/directories")
    public Object createDirectory(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.createDirectory(path, name);
        FileItem directory = storageService.describeVaultChild(path, name);
        activityLogService.record("CREATE_DIRECTORY", request, directory.path(), null, "Created directory " + name);
        FlashNotification notification = FlashNotification.success("Directory created.");
        String redirect = redirectToFiles(path, view, sort, direction, 1, size);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/rename")
    public Object rename(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("newName") String newName,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileItem oldItem = storageService.describeVaultChild(path, item);
        storageService.rename(path, item, newName);
        FileItem newItem = storageService.describeVaultChild(path, newName);
        thumbnailService.migrateVideoThumbnails(
                storageService.resolveVaultPath(newItem.path()),
                oldItem.path(),
                newItem.path()
        );
        shareLinkService.moveVaultPath(oldItem.path(), newItem.path());
        favoriteService.moveVaultPath(oldItem.path(), newItem.path());
        recentService.moveVaultPath(oldItem.path(), newItem.path());
        activityLogService.record("RENAME", request, oldItem.path(), newItem.path(), "Renamed item to " + newItem.name());
        FlashNotification notification = FlashNotification.success("Item renamed.");
        String redirect = redirectToFiles(path);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/detail/rename")
    public Object renameFromDetail(
            @RequestParam("path") String path,
            @RequestParam("newName") String newName,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        String newPath = storageService.renameVaultPath(detail.path(), newName);
        thumbnailService.migrateVideoThumbnails(storageService.resolveVaultPath(newPath), detail.path(), newPath);
        shareLinkService.moveVaultPath(detail.path(), newPath);
        favoriteService.moveVaultPath(detail.path(), newPath);
        recentService.moveVaultPath(detail.path(), newPath);
        activityLogService.record("RENAME", request, detail.path(), newPath, "Renamed item to " + newName);
        FlashNotification notification = FlashNotification.success("Item renamed.");
        String redirect = redirectToDetail(newPath);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/move")
    public Object move(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("targetPath") String targetPath,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileItem oldItem = storageService.describeVaultChild(path, item);
        storageService.move(path, item, targetPath);
        FileItem newItem = storageService.describeVaultChild(targetPath, item);
        thumbnailService.migrateVideoThumbnails(
                storageService.resolveVaultPath(newItem.path()),
                oldItem.path(),
                newItem.path()
        );
        shareLinkService.moveVaultPath(oldItem.path(), newItem.path());
        favoriteService.moveVaultPath(oldItem.path(), newItem.path());
        recentService.moveVaultPath(oldItem.path(), newItem.path());
        activityLogService.record("MOVE", request, oldItem.path(), newItem.path(), "Moved item to " + targetPath);
        FlashNotification notification = FlashNotification.success("Item moved.");
        String redirect = redirectToFiles(path);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/detail/move")
    public Object moveFromDetail(
            @RequestParam("path") String path,
            @RequestParam("targetPath") String targetPath,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        String newPath = storageService.moveVaultPath(detail.path(), targetPath);
        thumbnailService.migrateVideoThumbnails(storageService.resolveVaultPath(newPath), detail.path(), newPath);
        shareLinkService.moveVaultPath(detail.path(), newPath);
        favoriteService.moveVaultPath(detail.path(), newPath);
        recentService.moveVaultPath(detail.path(), newPath);
        activityLogService.record("MOVE", request, detail.path(), newPath, "Moved item to " + targetPath);
        FlashNotification notification = FlashNotification.success("Item moved.");
        String redirect = redirectToDetail(newPath);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/delete")
    public Object delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> items = SelectedItems.from(request);
        if (items.isEmpty()) {
            if (wantsJson(request)) {
                return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one item."));
            }
            FlashNotifications.warning(redirectAttributes, "Select at least one item.");
            return redirectToFiles(path, view, sort, direction, page, size);
        }
        List<TrashRecord> trashRecords = trashService.moveToTrash(path, items);
        for (TrashRecord record : trashRecords) {
            activityLogService.record("TRASH_MOVE", request, record.originalPath(), null, "Moved item to trash");
        }
        FlashNotification notification = FlashNotification.success("Selected items moved to trash.");
        String redirect = redirectToFiles(path, view, sort, direction, page, size);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/detail/delete")
    public Object deleteFromDetail(
            @RequestParam("path") String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        TrashRecord record = trashService.moveVaultPathToTrash(detail.path());
        activityLogService.record("TRASH_MOVE", request, record.originalPath(), null, "Moved item to trash");
        FlashNotification notification = FlashNotification.success("Item moved to trash.");
        String redirect = redirectToFiles(detail.parentPath());
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/share")
    public Object share(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam(value = "expiresInDays", required = false) String expiresInDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        ShareLink shareLink = shareLinkService.create(path, item, expiresAt(expiresInDays), customToken);
        String shareUrl = shareUrl(shareLink);
        activityLogService.record(
                "SHARE_CREATE",
                request,
                shareLink.path(),
                null,
                "Created share link " + shareLink.token(),
                Map.of("token", shareLink.token(), "type", shareLink.type().name())
        );
        FlashNotification notification = FlashNotification.info("Share link created.", "Copy link", shareUrl);

        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification, ShareLinkPayload.from(shareLink, shareUrl)));
        }

        FlashNotifications.info(redirectAttributes, notification.message(), notification.actionLabel(), shareUrl);
        return redirectToFiles(path);
    }

    @PostMapping("/files/detail/share")
    public Object shareFromDetail(
            @RequestParam("path") String path,
            @RequestParam(value = "expiresInDays", required = false) String expiresInDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        ShareLink shareLink = shareLinkService.createForVaultPath(detail.path(), expiresAt(expiresInDays), customToken);
        String shareUrl = shareUrl(shareLink);
        activityLogService.record(
                "SHARE_CREATE",
                request,
                shareLink.path(),
                null,
                "Created share link " + shareLink.token(),
                Map.of("token", shareLink.token(), "type", shareLink.type().name())
        );
        FlashNotification notification = FlashNotification.info("Share link created.", "Copy link", shareUrl);

        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification, ShareLinkPayload.from(shareLink, shareUrl)));
        }

        FlashNotifications.info(redirectAttributes, notification.message(), notification.actionLabel(), shareUrl);
        return redirectToDetail(detail.path());
    }

    @PostMapping("/files/detail/shares/revoke")
    public Object revokeShareFromDetail(
            @RequestParam("path") String path,
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        shareLinkService.revoke(token);
        activityLogService.record("SHARE_REVOKE", request, detail.path(), null, "Revoked share link " + token);
        FlashNotification notification = FlashNotification.success("Share link revoked.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectToDetail(detail.path());
    }

    @PostMapping("/files/detail/shares/delete")
    public Object deleteShareFromDetail(
            @RequestParam("path") String path,
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        shareLinkService.delete(token);
        activityLogService.record("SHARE_DELETE", request, detail.path(), null, "Deleted share link " + token);
        FlashNotification notification = FlashNotification.success("Share link deleted.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectToDetail(detail.path());
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
            response.sendRedirect(redirectUrl(redirectToFiles(path, view, sort, direction, page, size)));
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

    private String redirectUrl(String redirectViewName) {
        return redirectViewName.startsWith("redirect:") ? redirectViewName.substring("redirect:".length()) : redirectViewName;
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
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

    private Instant expiresAt(String expiresInDays) {
        if (expiresInDays == null || expiresInDays.isBlank()) {
            return null;
        }

        long days;
        try {
            days = Long.parseLong(expiresInDays.trim());
        } catch (NumberFormatException ex) {
            throw new StorageAccessException("Expiration days must be a whole number.");
        }

        if (days <= 0) {
            throw new StorageAccessException("Expiration days must be 1 or greater.");
        }

        try {
            return Instant.now().plus(Duration.ofDays(days));
        } catch (ArithmeticException | DateTimeException ex) {
            throw new StorageAccessException("Expiration days is too large.");
        }
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

    private String normalizeSearchQuery(String query) {
        return query == null ? "" : query.trim();
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
