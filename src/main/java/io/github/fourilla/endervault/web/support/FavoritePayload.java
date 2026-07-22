package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.favorite.FavoriteItem;

public record FavoritePayload(
        String path,
        String name,
        boolean directory,
        String iconClass,
        String openUrl,
        String directOpenUrl,
        String detailUrl,
        boolean openInNewTab,
        boolean hidden
) {
    public static FavoritePayload from(FavoriteItem favorite) {
        return from(favorite, "open");
    }

    public static FavoritePayload from(FavoriteItem favorite, String bookmarkLinkClickAction) {
        return from(favorite, bookmarkLinkClickAction, false);
    }

    public static FavoritePayload from(FavoriteItem favorite, String bookmarkLinkClickAction, boolean hidden) {
        return new FavoritePayload(
                favorite.path(),
                favorite.name(),
                favorite.directory(),
                favorite.iconClass(),
                favorite.openUrl(bookmarkLinkClickAction),
                favorite.directOpenUrl(),
                favorite.detailUrl(),
                favorite.opensInNewTab(bookmarkLinkClickAction),
                hidden
        );
    }
}
