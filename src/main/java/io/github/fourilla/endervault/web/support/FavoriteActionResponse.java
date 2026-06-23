package io.github.fourilla.endervault.web.support;

public record FavoriteActionResponse(
        boolean ok,
        FlashNotification notification,
        boolean active,
        String path,
        FavoritePayload favorite
) {
    public static FavoriteActionResponse added(FlashNotification notification, FavoritePayload favorite) {
        return new FavoriteActionResponse(true, notification, true, favorite.path(), favorite);
    }

    public static FavoriteActionResponse removed(FlashNotification notification, String path) {
        return new FavoriteActionResponse(true, notification, false, path, null);
    }
}
