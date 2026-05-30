package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.share.ShareLinkService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class AdminShareController {

    private final ShareLinkService shareLinkService;
    private final ActivityLogService activityLogService;

    public AdminShareController(ShareLinkService shareLinkService, ActivityLogService activityLogService) {
        this.shareLinkService = shareLinkService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/shares")
    public String shares(Model model) throws IOException {
        model.addAttribute("shares", shareLinkService.list());
        model.addAttribute("shareBaseUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString());
        return "shares";
    }

    @PostMapping("/files/shares/revoke")
    public Object revoke(
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    )
            throws IOException {
        shareLinkService.revoke(token);
        activityLogService.record("SHARE_REVOKE", request, null, null, "Revoked share link " + token);
        FlashNotification notification = FlashNotification.success("Share link revoked.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return "redirect:/files/shares";
    }

    @PostMapping("/files/shares/delete")
    public Object delete(
            @RequestParam("token") String token,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    )
            throws IOException {
        shareLinkService.delete(token);
        activityLogService.record("SHARE_DELETE", request, null, null, "Deleted share link " + token);
        FlashNotification notification = FlashNotification.success("Share link deleted.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return "redirect:/files/shares";
    }

    @PostMapping("/files/shares/delete-expired")
    public Object deleteExpired(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        int deletedCount = shareLinkService.deleteExpired(Instant.now());
        activityLogService.record("SHARE_DELETE_EXPIRED", request, null, null,
                "Deleted " + deletedCount + " expired share links.");
        FlashNotification notification =
                FlashNotification.success("Deleted %d expired share links.".formatted(deletedCount));
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return "redirect:/files/shares";
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
