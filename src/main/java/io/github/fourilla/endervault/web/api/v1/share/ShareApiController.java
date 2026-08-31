package io.github.fourilla.endervault.web.api.v1.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ShareUrlBuilder shareUrlBuilder;
    private final NasProperties.Share shareProperties;

    public ShareApiController(
            ShareLinkService shareLinkService,
            ActivityLogService activityLogService,
            PublicLinkTokenService publicLinkTokenService,
            ShareUrlBuilder shareUrlBuilder,
            NasProperties nasProperties
    ) {
        this.shareLinkService = shareLinkService;
        this.activityLogService = activityLogService;
        this.publicLinkTokenService = publicLinkTokenService;
        this.shareUrlBuilder = shareUrlBuilder;
        this.shareProperties = nasProperties.getShare();
    }

    @GetMapping
    public List<ShareLinkPayload> list() throws IOException {
        String shareBaseUrl = shareUrlBuilder.shareBaseUrl();
        boolean directDownloadEnabled = shareUrlBuilder.directDownloadLinkEnabled();
        return shareLinkService.list().stream()
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, directDownloadEnabled))
                .map(ShareLinkPayload::from)
                .toList();
    }

    @PostMapping
    public ActionResponse create(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "item", required = false) String item,
            @RequestParam(value = "expiresInDays", required = false) String expiresInDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            HttpServletRequest request
    ) throws IOException {
        ShareLink shareLink = item == null || item.isBlank()
                ? shareLinkService.createForVaultPath(path, expiresAt(expiresInDays), customToken)
                : shareLinkService.create(path, item, expiresAt(expiresInDays), customToken);
        ShareLinkView shareView = ShareLinkView.from(
                shareLink,
                shareUrlBuilder.shareBaseUrl(),
                shareUrlBuilder.directDownloadLinkEnabled()
        );
        activityLogService.record(
                "SHARE_CREATE",
                request,
                shareLink.path(),
                null,
                "Created share link",
                shareMetadata(shareLink)
        );
        FlashNotification notification = FlashNotification.info("Share link created.", "Copy link", shareView.url());
        return ActionResponse.ok(notification, ShareLinkPayload.from(shareView));
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

    private Map<String, String> shareMetadata(ShareLink shareLink) {
        return Map.of(
                "tokenFingerprint", publicLinkTokenService.fingerprint(shareLink.token()),
                "type", shareLink.type().name()
        );
    }

    private Instant expiresAt(String expiresInDays) {
        if (expiresInDays == null || expiresInDays.isBlank()) {
            int defaultDays = Math.max(0, shareProperties.getDefaultExpirationDays());
            if (defaultDays > 0) {
                validateMaxExpirationDays(defaultDays);
                return expirationFromDays(defaultDays);
            }
            if (shareProperties.isAllowNeverExpires()) {
                return null;
            }
            throw new StorageAccessException("Share expiration is required.");
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
        validateMaxExpirationDays(days);
        return expirationFromDays(days);
    }

    private void validateMaxExpirationDays(long days) {
        int maxDays = shareProperties.getMaxExpirationDays();
        if (maxDays > 0 && days > maxDays) {
            throw new StorageAccessException("Expiration days must be " + maxDays + " or less.");
        }
    }

    private Instant expirationFromDays(long days) {
        try {
            return Instant.now().plus(Duration.ofDays(days));
        } catch (ArithmeticException | DateTimeException ex) {
            throw new StorageAccessException("Expiration days is too large.");
        }
    }
}
