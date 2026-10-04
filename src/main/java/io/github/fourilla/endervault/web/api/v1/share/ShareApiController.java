package io.github.fourilla.endervault.web.api.v1.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.share.ShareLinkSearchSchema;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shares")
public class ShareApiController {

    private static final Logger logger = LoggerFactory.getLogger(ShareApiController.class);
    static final int MAX_BULK_ITEMS = 200;

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
    public List<ShareLinkPayload> list(@RequestParam(value = "q", required = false) String query) throws IOException {
        Instant now = Instant.now();
        var filter = ShareLinkSearchSchema.compile(query, now);
        String shareBaseUrl = shareUrlBuilder.shareBaseUrl();
        boolean directDownloadEnabled = shareUrlBuilder.directDownloadLinkEnabled();
        return shareLinkService.list().stream()
                .filter(filter)
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, directDownloadEnabled))
                .map(link -> ShareLinkPayload.from(link, now))
                .toList();
    }

    @PostMapping
    public ActionResponse create(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "item", required = false) String item,
            @RequestParam(value = "expiresInDays", required = false) String expiresInDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            @RequestParam(value = "previewEnabled", required = false) Boolean previewEnabled,
            HttpServletRequest request
    ) throws IOException {
        boolean effectivePreviewEnabled = previewEnabled != null
                ? previewEnabled
                : shareProperties.isDefaultPreviewEnabled();
        ShareLink shareLink = item == null || item.isBlank()
                ? shareLinkService.createForVaultPath(
                        path, expiresAt(expiresInDays), customToken, effectivePreviewEnabled)
                : shareLinkService.create(
                        path, item, expiresAt(expiresInDays), customToken, effectivePreviewEnabled);
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

    @PostMapping("/selected/resolve")
    public ResponseEntity<?> resolveSelected(
            @RequestParam(value = "tokens", required = false) List<String> tokens,
            @RequestParam(value = "action", required = false) BulkAction action,
            @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
            HttpServletRequest request
    ) {
        List<String> selected;
        try {
            selected = validateSelection(tokens, action, confirmed);
        } catch (StorageAccessException ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
        }
        var results = new java.util.ArrayList<BulkItemResponse>();
        for (String token : selected) {
            try {
                // Preserve single-item idempotency and fingerprint-only activity records.
                ActionResponse result = action == BulkAction.REVOKE ? revoke(token, request) : delete(token, request);
                results.add(new BulkItemResponse(token, BulkStatus.APPLIED, result.notification().message()));
            } catch (NoSuchFileException ex) {
                results.add(new BulkItemResponse(token, BulkStatus.NOT_FOUND,
                        "This share link is no longer available. Refresh the list."));
            } catch (StorageAccessException ex) {
                results.add(new BulkItemResponse(token, BulkStatus.REJECTED,
                        "This action was rejected. Refresh shared links before retrying."));
            } catch (IOException | RuntimeException ex) {
                logger.warn("Selected share {} action failed for token fingerprint {}.",
                        action, publicLinkTokenService.fingerprint(token));
                results.add(new BulkItemResponse(token, BulkStatus.FAILED,
                        "Could not complete this action. Refresh shared links before retrying."));
            }
        }
        int succeeded = (int) results.stream().filter(item -> item.status() == BulkStatus.APPLIED).count();
        int failed = results.size() - succeeded;
        String message = "Share links processed: " + succeeded + " succeeded, " + failed + " unsuccessful.";
        FlashNotification notification = failed == 0 ? FlashNotification.success(message)
                : succeeded == 0 ? FlashNotification.error(message) : FlashNotification.warning(message);
        return ResponseEntity.ok(new BulkResponse(true, notification, succeeded, failed, List.copyOf(results)));
    }

    private static List<String> validateSelection(List<String> tokens, BulkAction action, boolean confirmed) {
        if (action == null || !confirmed) {
            throw new StorageAccessException("Select a supported action and confirm the selected share links.");
        }
        if (tokens == null || tokens.isEmpty() || tokens.size() > MAX_BULK_ITEMS) {
            throw new StorageAccessException("Select between 1 and " + MAX_BULK_ITEMS + " share links.");
        }
        var unique = new HashSet<String>();
        for (String token : tokens) {
            if (token == null || !token.matches("[A-Za-z0-9_-]+")) {
                throw new StorageAccessException("Selected share tokens contain invalid characters.");
            }
            if (!unique.add(token)) {
                throw new StorageAccessException("Selected share tokens must not contain duplicates.");
            }
        }
        return List.copyOf(tokens);
    }

    public enum BulkAction { REVOKE, DELETE }
    public enum BulkStatus { APPLIED, NOT_FOUND, REJECTED, FAILED }
    public record BulkItemResponse(String id, BulkStatus status, String message) { }
    public record BulkResponse(
            boolean ok, FlashNotification notification, int succeededCount, int failedCount, List<BulkItemResponse> results
    ) { }

    private Map<String, String> shareMetadata(String token) {
        return Map.of("tokenFingerprint", publicLinkTokenService.fingerprint(token));
    }

    private Map<String, String> shareMetadata(ShareLink shareLink) {
        return Map.of(
                "tokenFingerprint", publicLinkTokenService.fingerprint(shareLink.token()),
                "type", shareLink.type().name(),
                "previewEnabled", Boolean.toString(shareLink.previewEnabled())
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
