package io.github.fourilla.endervault.web.api.v1.favorite;

import io.github.fourilla.endervault.favorite.FavoriteDisplayItem;
import java.util.List;

public record FavoriteBrowserPayload(
        List<FavoriteBrowserItemPayload> items
) {
    public static FavoriteBrowserPayload from(
            List<FavoriteDisplayItem> favorites,
            String bookmarkLinkClickAction
    ) {
        return new FavoriteBrowserPayload(favorites.stream()
                .map(favorite -> FavoriteBrowserItemPayload.from(favorite, bookmarkLinkClickAction))
                .toList());
    }

    public record FavoriteBrowserItemPayload(
            String path,
            String name,
            String typeLabel,
            String iconClass,
            String openUrl,
            String directOpenUrl,
            String detailUrl,
            boolean openInNewTab,
            boolean bookmarkLink,
            boolean hidden,
            String targetLabel,
            String createdLabel
    ) {
        private static FavoriteBrowserItemPayload from(
                FavoriteDisplayItem favorite,
                String bookmarkLinkClickAction
        ) {
            return new FavoriteBrowserItemPayload(
                    favorite.path(),
                    favorite.name(),
                    favorite.typeLabel(),
                    favorite.iconClass(),
                    favorite.openUrl(bookmarkLinkClickAction),
                    favorite.directOpenUrl(),
                    favorite.detailUrl(),
                    favorite.opensInNewTab(bookmarkLinkClickAction),
                    favorite.bookmarkLink(),
                    favorite.hidden(),
                    favorite.targetLabel(),
                    favorite.createdLabel()
            );
        }
    }
}
