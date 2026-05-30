package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.favorite.FavoriteItem;

public record FavoritePayload(
        String path,
        String name,
        boolean directory,
        String iconClass
) {
    public static FavoritePayload from(FavoriteItem favorite) {
        return new FavoritePayload(
                favorite.path(),
                favorite.name(),
                favorite.directory(),
                favorite.iconClass()
        );
    }
}
