package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileMutationController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;

    public AdminFileMutationController(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            TrashService trashService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
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
        thumbnailService.migrateThumbnails(
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
        thumbnailService.migrateThumbnails(storageService.resolveVaultPath(newPath), detail.path(), newPath);
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
        thumbnailService.migrateThumbnails(
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
        thumbnailService.migrateThumbnails(storageService.resolveVaultPath(newPath), detail.path(), newPath);
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

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
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
}
