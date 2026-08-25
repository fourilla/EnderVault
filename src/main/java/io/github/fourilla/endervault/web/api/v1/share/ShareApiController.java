package io.github.fourilla.endervault.web.api.v1.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shares")
public class ShareApiController {

    private final ShareLinkService shareLinkService;
    private final ActivityLogService activityLogService;
    private final PublicLinkTokenService publicLinkTokenService;

    public ShareApiController(
            ShareLinkService shareLinkService,
            ActivityLogService activityLogService,
            PublicLinkTokenService publicLinkTokenService
    ) {
        this.shareLinkService = shareLinkService;
        this.activityLogService = activityLogService;
        this.publicLinkTokenService = publicLinkTokenService;
    }

    @PostMapping("/revoke")
    public ActionResponse revoke(
            @RequestParam("token") String token,
            HttpServletRequest request
    ) throws IOException {
        shareLinkService.revoke(token);
        activityLogService.record(
                "SHARE_REVOKE",
                request,
                null,
                null,
                "Revoked share link",
                shareMetadata(token)
        );
        return ActionResponse.ok(FlashNotification.success("Share link revoked."));
    }

    @PostMapping("/delete")
    public ActionResponse delete(
            @RequestParam("token") String token,
            HttpServletRequest request
    ) throws IOException {
        shareLinkService.delete(token);
        activityLogService.record(
                "SHARE_DELETE",
                request,
                null,
                null,
                "Deleted share link",
                shareMetadata(token)
        );
        return ActionResponse.ok(FlashNotification.success("Share link deleted."));
    }

    @PostMapping("/expired/delete")
    public ActionResponse deleteExpired(HttpServletRequest request) throws IOException {
        int deletedCount = shareLinkService.deleteExpired(Instant.now());
        activityLogService.record(
                "SHARE_DELETE_EXPIRED",
                request,
                null,
                null,
                "Deleted " + deletedCount + " expired share links."
        );
        return ActionResponse.ok(FlashNotification.success(
                "Deleted %d expired share links.".formatted(deletedCount)
        ));
    }

    private Map<String, String> shareMetadata(String token) {
        return Map.of("tokenFingerprint", publicLinkTokenService.fingerprint(token));
    }
}
