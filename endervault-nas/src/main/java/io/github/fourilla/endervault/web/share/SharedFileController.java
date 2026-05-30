package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.share.ShareTargetType;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.FileResponseService;
import io.github.fourilla.endervault.web.support.SelectedItems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SharedFileController {

    private final ShareLinkService shareLinkService;
    private final StorageService storageService;
    private final FileResponseService fileResponseService;
    private final ActivityLogService activityLogService;

    public SharedFileController(
            ShareLinkService shareLinkService,
            StorageService storageService,
            FileResponseService fileResponseService,
            ActivityLogService activityLogService
    ) {
        this.shareLinkService = shareLinkService;
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/s/{token}")
    public String shared(
            @PathVariable String token,
            @RequestParam(value = "path", required = false) String path,
            HttpServletRequest request,
            Model model
    ) throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        activityLogService.record("SHARE_ACCESS", request, shareLink.path(), path, "Accessed share link " + token);
        model.addAttribute("share", shareLink);
        model.addAttribute("token", token);

        if (shareLink.type() == ShareTargetType.FILE) {
            FileItem item = storageService.describeVaultPath(shareLink.path());
            model.addAttribute("item", item);
            return "shared-file";
        }

        DirectoryListing listing = storageService.listSharedDirectory(shareLink.path(), path);
        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        return "shared-directory";
    }

    @GetMapping("/s/{token}/download")
    public ResponseEntity<?> download(
            @PathVariable String token,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "item", required = false) String item,
            HttpServletRequest request
    ) throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        Path file = resolveSharedDownloadTarget(shareLink, path, item);
        activityLogService.record("SHARE_DOWNLOAD", request, shareLink.path(), item, "Downloaded from share link " + token);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/s/{token}/download.zip")
    public void downloadZip(
            @PathVariable String token,
            @RequestParam(value = "path", required = false) String path,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        if (shareLink.type() != ShareTargetType.DIRECTORY) {
            throw new NoSuchFileException(token);
        }

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shared-files.zip\"");
        List<String> items = SelectedItems.from(request);
        if (!items.isEmpty()) {
            activityLogService.record("SHARE_DOWNLOAD_ZIP", request, shareLink.path(), path,
                    "Downloaded shared ZIP with " + items.size() + " item(s)");
            storageService.writeSharedZip(shareLink.path(), path, items, response.getOutputStream());
        }
    }

    @GetMapping("/s/{token}/preview")
    public ResponseEntity<?> preview(
            @PathVariable String token,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "item", required = false) String item,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        Path file = resolveSharedDownloadTarget(shareLink, path, item);
        return fileResponseService.inline(file, headers);
    }

    private Path resolveSharedDownloadTarget(ShareLink shareLink, String path, String item) throws IOException {
        if (shareLink.type() == ShareTargetType.FILE) {
            return storageService.resolveVaultFile(shareLink.path());
        }
        if (item == null || item.isBlank()) {
            throw new NoSuchFileException(shareLink.token());
        }
        return storageService.resolveSharedFile(shareLink.path(), path, item);
    }
}
