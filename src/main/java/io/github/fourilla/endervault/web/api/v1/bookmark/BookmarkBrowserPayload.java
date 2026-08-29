package io.github.fourilla.endervault.web.api.v1.bookmark;

import io.github.fourilla.endervault.bookmark.BookmarkBreadcrumb;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.web.bookmark.BookmarkRoutes;
import io.github.fourilla.endervault.web.support.BookmarkLinkClickAction;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public record BookmarkBrowserPayload(
        List<BreadcrumbPayload> breadcrumbs,
        String currentDirectoryId,
        List<BookmarkEntryPayload> directories,
        List<BookmarkEntryPayload> links,
        SearchPayload search,
        boolean metadataFetchEnabled,
        String linkClickAction,
        int totalItems
) {

    static BookmarkBrowserPayload from(
            List<BookmarkItem> items,
            List<BookmarkBreadcrumb> breadcrumbs,
            String currentDirectoryId,
            String query,
            boolean metadataFetchEnabled,
            String linkClickAction,
            Set<String> favoriteIds
    ) {
        List<BookmarkEntryPayload> directories = items.stream()
                .filter(BookmarkItem::directory)
                .map(item -> entry(item, favoriteIds, metadataFetchEnabled, linkClickAction))
                .toList();
        List<BookmarkEntryPayload> links = items.stream()
                .filter(BookmarkItem::link)
                .map(item -> entry(item, favoriteIds, metadataFetchEnabled, linkClickAction))
                .toList();
        return new BookmarkBrowserPayload(
                breadcrumbs.stream().map(BreadcrumbPayload::from).toList(),
                currentDirectoryId,
                directories,
                links,
                new SearchPayload(query, !query.isBlank()),
                metadataFetchEnabled,
                linkClickAction,
                directories.size() + links.size()
        );
    }

    private static BookmarkEntryPayload entry(
            BookmarkItem item,
            Set<String> favoriteIds,
            boolean metadataFetchEnabled,
            String linkClickAction
    ) {
        return BookmarkEntryPayload.from(
                item,
                favoriteIds.contains(item.id()),
                metadataFetchEnabled,
                linkClickAction
        );
    }

    public record BreadcrumbPayload(String id, String label) {
        static BreadcrumbPayload from(BookmarkBreadcrumb breadcrumb) {
            return new BreadcrumbPayload(breadcrumb.id(), breadcrumb.label());
        }
    }

    public record SearchPayload(String query, boolean performed) {
    }

    public record BookmarkEntryPayload(
            String id,
            String type,
            String title,
            String url,
            boolean external,
            boolean favorite,
            boolean metadataRefreshable,
            boolean faviconAvailable,
            Instant updatedAt,
            String updatedLabel,
            String openUrl,
            String detailUrl,
            String faviconUrl,
            String primaryUrl,
            boolean primaryNewTab
    ) {
        static BookmarkEntryPayload from(
                BookmarkItem item,
                boolean favorite,
                boolean metadataFetchEnabled,
                String linkClickAction
        ) {
            String openUrl = item.directory()
                    ? BookmarkRoutes.bookmarksUrl(item.id(), null)
                    : BookmarkRoutes.bookmarkOpenUrl(item.id());
            String detailUrl = BookmarkRoutes.bookmarkDetailUrl(item.id());
            boolean primaryOpensLink = item.link() && BookmarkLinkClickAction.OPEN.equals(linkClickAction);
            return new BookmarkEntryPayload(
                    item.id(),
                    item.directory() ? "directory" : "link",
                    item.title(),
                    item.url(),
                    item.externalLink(),
                    favorite,
                    metadataFetchEnabled && item.externalLink(),
                    item.faviconAvailable(),
                    item.updatedAt(),
                    item.updatedLabel(),
                    openUrl,
                    detailUrl,
                    item.faviconAvailable() ? BookmarkRoutes.bookmarkFaviconUrl(item.id()) : null,
                    primaryOpensLink ? openUrl : item.directory() ? openUrl : detailUrl,
                    primaryOpensLink
            );
        }

    }
}
