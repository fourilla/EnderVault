package io.github.fourilla.endervault.web.support;

import java.time.Instant;

public record ShareLinkPayload(
        String token,
        String url,
        String directDownloadUrl,
        String path,
        String type,
        String createdLabel,
        String expiresLabel,
        String statusLabel,
        String statusClass,
        boolean previewEnabled,
        boolean active
) {

    public static ShareLinkPayload from(ShareLinkView shareLink) {
        return from(shareLink, Instant.now());
    }

    public static ShareLinkPayload from(ShareLinkView shareLink, Instant now) {
        return new ShareLinkPayload(
                shareLink.token(),
                shareLink.url(),
                shareLink.directDownloadUrl(),
                shareLink.path(),
                shareLink.type().name(),
                shareLink.createdLabel(),
                shareLink.expiresLabel(),
                shareLink.shareLink().statusLabel(now),
                shareLink.shareLink().statusClass(now),
                shareLink.previewEnabled(),
                shareLink.shareLink().usable(now)
        );
    }

}
