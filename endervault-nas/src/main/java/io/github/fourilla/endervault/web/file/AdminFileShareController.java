package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileShareController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final ActivityLogService activityLogService;

    public AdminFileShareController(
            StorageService storageService,
            ShareLinkService shareLinkService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.activityLogService = activityLogService;
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
        ShareLinkView shareView = shareView(shareLink);
        activityLogService.record(
                "SHARE_CREATE",
                request,
                shareLink.path(),
                null,
                "Created share link " + shareLink.token(),
                Map.of("token", shareLink.token(), "type", shareLink.type().name())
        );
        FlashNotification notification = FlashNotification.info("Share link created.", "Copy link", shareView.url());
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToFiles(path),
                ActionResponse.ok(notification, ShareLinkPayload.from(shareView))
        );
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
        ShareLinkView shareView = shareView(shareLink);
        activityLogService.record(
                "SHARE_CREATE",
                request,
                shareLink.path(),
                null,
                "Created share link " + shareLink.token(),
                Map.of("token", shareLink.token(), "type", shareLink.type().name())
        );
        FlashNotification notification = FlashNotification.info("Share link created.", "Copy link", shareView.url());
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToDetail(detail.path()),
                ActionResponse.ok(notification, ShareLinkPayload.from(shareView))
        );
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
        return ActionResponseSupport.ok(request, redirectAttributes, notification, redirectToDetail(detail.path()));
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
        return ActionResponseSupport.ok(request, redirectAttributes, notification, redirectToDetail(detail.path()));
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
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

    private ShareLinkView shareView(ShareLink shareLink) {
        return ShareLinkView.from(shareLink, ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString());
    }

    private String redirectToFiles(String path) {
        if (path == null || path.isBlank()) {
            return "redirect:/files";
        }
        return "redirect:/files?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }

}
