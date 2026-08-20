package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminShareController {

    private final ShareLinkService shareLinkService;
    private final ActivityLogService activityLogService;
    private final ShareUrlBuilder shareUrlBuilder;
    private final PublicLinkTokenService publicLinkTokenService;

    public AdminShareController(
            ShareLinkService shareLinkService,
            ActivityLogService activityLogService,
            ShareUrlBuilder shareUrlBuilder,
            PublicLinkTokenService publicLinkTokenService
    ) {
        this.shareLinkService = shareLinkService;
        this.activityLogService = activityLogService;
        this.shareUrlBuilder = shareUrlBuilder;
        this.publicLinkTokenService = publicLinkTokenService;
    }

    @GetMapping("/admin/shares")
    public String shares(Model model) throws IOException {
        String shareBaseUrl = shareBaseUrl();
        model.addAttribute("shares", shareLinkService.list()
                .stream()
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, shareUrlBuilder.directDownloadLinkEnabled()))
                .toList());
        return "shares";
    }

    @PostMapping("/admin/shares/revoke")
    public Object revoke(
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    )
            throws IOException {
        shareLinkService.revoke(token);
        activityLogService.record(
                "SHARE_REVOKE",
                request,
                null,
                null,
                "Revoked share link",
                shareMetadata(token)
        );
        FlashNotification notification = FlashNotification.success("Share link revoked.");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/admin/shares");
    }

    @PostMapping("/admin/shares/delete")
    public Object delete(
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    )
            throws IOException {
        shareLinkService.delete(token);
        activityLogService.record(
                "SHARE_DELETE",
                request,
                null,
                null,
                "Deleted share link",
                shareMetadata(token)
        );
        FlashNotification notification = FlashNotification.success("Share link deleted.");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/admin/shares");
    }

    @PostMapping("/admin/shares/delete-expired")
    public Object deleteExpired(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        int deletedCount = shareLinkService.deleteExpired(Instant.now());
        activityLogService.record("SHARE_DELETE_EXPIRED", request, null, null,
                "Deleted " + deletedCount + " expired share links.");
        FlashNotification notification =
                FlashNotification.success("Deleted %d expired share links.".formatted(deletedCount));
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/admin/shares");
    }

    private String shareBaseUrl() {
        return shareUrlBuilder.shareBaseUrl();
    }

    private Map<String, String> shareMetadata(String token) {
        return Map.of("tokenFingerprint", publicLinkTokenService.fingerprint(token));
    }
}
