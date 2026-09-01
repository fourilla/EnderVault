package io.github.fourilla.endervault.web.api.v1.bookmark;

import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkLogMetadata;
import io.github.fourilla.endervault.web.bookmark.BookmarkRoutes;

public record BookmarkDetailPayload(
        String id,
        String type,
        String typeLabel,
        String title,
        String url,
        String note,
        boolean directory,
        boolean link,
        boolean external,
        boolean favorite,
        boolean metadataRefreshable,
        String titleSource,
        boolean metadataFetchAttempted,
        String metadataFetchStatus,
        String metadataFetchedLabel,
        boolean faviconAvailable,
        String faviconContentType,
        String faviconUrl,
        String iconClass,
        String createdLabel,
        String updatedLabel,
        String lastOpenedLabel,
        String parentId,
        String parentLabel,
        String parentUrl,
        String openUrl,
        String directoryUrl
) {

    static BookmarkDetailPayload from(
            BookmarkItem item,
            boolean favorite,
            boolean metadataFetchEnabled,
            BookmarkItem parent
    ) {
        String parentId = BookmarkRoutes.normalizeId(item.parentId());
        return new BookmarkDetailPayload(
                item.id(),
                item.directory() ? "directory" : "link",
                item.typeLabel(),
                item.title(),
                item.safeUrl(),
                item.safeNote(),
                item.directory(),
                item.link(),
                item.externalLink(),
                favorite,
                metadataFetchEnabled && item.externalLink(),
                item.effectiveTitleSource().name(),
                BookmarkLogMetadata.metadataFetchAttempted(item),
                BookmarkLogMetadata.metadataFetchStatus(item),
                item.metadataFetchedLabel(),
                item.faviconAvailable(),
                item.faviconAvailable() ? item.faviconContentType() : "-",
                item.faviconAvailable() ? BookmarkRoutes.bookmarkFaviconUrl(item.id()) : null,
                item.iconClass(),
                item.createdLabel(),
                item.updatedLabel(),
                item.lastOpenedLabel(),
                parentId,
                parent == null ? "Bookmarks" : parent.title(),
                BookmarkRoutes.bookmarksUrl(parentId, null),
                item.link() ? BookmarkRoutes.bookmarkOpenUrl(item.id()) : null,
                item.directory() ? BookmarkRoutes.bookmarksUrl(item.id(), null) : null
        );
    }
}
