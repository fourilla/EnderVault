package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareTargetType;
import org.springframework.web.util.UriComponentsBuilder;

public record ShareLinkView(
        ShareLink shareLink,
        String url,
        String directDownloadUrl
) {

    public static ShareLinkView from(ShareLink shareLink, String shareBaseUrl) {
        return from(shareLink, shareBaseUrl, true);
    }

    public static ShareLinkView from(ShareLink shareLink, String shareBaseUrl, boolean directDownloadLinkEnabled) {
        String url = normalizeBaseUrl(shareBaseUrl) + shareLink.token();
        String directDownloadUrl = directDownloadLinkEnabled && shareLink.type() == ShareTargetType.FILE
                ? directDownloadUrl(url, shareLink.path())
                : null;
        return new ShareLinkView(shareLink, url, directDownloadUrl);
    }

    public String token() {
        return shareLink.token();
    }

    public String path() {
        return shareLink.path();
    }

    public ShareTargetType type() {
        return shareLink.type();
    }

    public String createdLabel() {
        return shareLink.createdLabel();
    }

    public String expiresLabel() {
        return shareLink.expiresLabel();
    }

    public String statusLabel() {
        return shareLink.statusLabel();
    }

    public String statusClass() {
        return shareLink.statusClass();
    }

    public boolean active() {
        return shareLink.active();
    }

    public boolean hasDirectDownloadUrl() {
        return directDownloadUrl != null && !directDownloadUrl.isBlank();
    }

    private static String normalizeBaseUrl(String shareBaseUrl) {
        if (shareBaseUrl == null || shareBaseUrl.isBlank()) {
            return "/s/";
        }
        return shareBaseUrl.endsWith("/") ? shareBaseUrl : shareBaseUrl + "/";
    }

    private static String directDownloadUrl(String shareUrl, String path) {
        return UriComponentsBuilder.fromUriString(shareUrl)
                .pathSegment("download", targetName(path))
                .build()
                .encode()
                .toUriString();
    }

    private static String targetName(String path) {
        if (path == null || path.isBlank()) {
            return "download";
        }
        String normalized = path.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String name = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return name.isBlank() ? "download" : name;
    }
}
