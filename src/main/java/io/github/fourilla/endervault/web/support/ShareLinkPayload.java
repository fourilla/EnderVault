package io.github.fourilla.endervault.web.support;

public record ShareLinkPayload(
        String token,
        String url,
        String directDownloadUrl,
        String createdLabel,
        String expiresLabel,
        String statusLabel,
        String statusClass,
        boolean active
) {

    public static ShareLinkPayload from(ShareLinkView shareLink) {
        return new ShareLinkPayload(
                shareLink.token(),
                shareLink.url(),
                shareLink.directDownloadUrl(),
                shareLink.createdLabel(),
                shareLink.expiresLabel(),
                shareLink.statusLabel(),
                shareLink.statusClass(),
                shareLink.active()
        );
    }

}
