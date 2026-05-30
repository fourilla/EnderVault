package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.share.ShareLink;

public record ShareLinkPayload(
        String token,
        String url,
        String createdLabel,
        String expiresLabel,
        String statusLabel,
        String statusClass,
        boolean active
) {

    public static ShareLinkPayload from(ShareLink shareLink, String url) {
        return new ShareLinkPayload(
                shareLink.token(),
                url,
                shareLink.createdLabel(),
                shareLink.expiresLabel(),
                shareLink.statusLabel(),
                shareLink.statusClass(),
                shareLink.active()
        );
    }
}
