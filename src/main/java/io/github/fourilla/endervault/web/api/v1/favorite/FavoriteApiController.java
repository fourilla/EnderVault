package io.github.fourilla.endervault.web.api.v1.favorite;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.BookmarkLinkClickAction;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import io.github.fourilla.endervault.web.support.FavoriteActionResponse;
import io.github.fourilla.endervault.web.support.FavoritePayload;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteApiController {

    private final FavoriteService favoriteService;
    private final NasProperties nasProperties;

    public FavoriteApiController(FavoriteService favoriteService, NasProperties nasProperties) {
        this.favoriteService = favoriteService;
        this.nasProperties = nasProperties;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public FavoriteBrowserPayload list(
            @RequestParam(value = "hidden", required = false) String hidden,
            HttpServletRequest request
    ) throws IOException {
        return FavoriteBrowserPayload.from(
                favoriteService.listDisplay(showHiddenFavorites(hidden, request)),
                BookmarkLinkClickAction.from(nasProperties)
        );
    }

    @PostMapping("/toggle")
    public FavoriteActionResponse toggle(@RequestParam("path") String path) throws IOException {
        return toggleResponse(path, favoriteService.toggle(path));
    }

    @PostMapping("/toggle-bookmark")
    public FavoriteActionResponse toggleBookmark(@RequestParam("id") String id) throws IOException {
        String path = "bookmark:" + (id == null ? "" : id.trim());
        return toggleResponse(path, favoriteService.toggleBookmark(id));
    }

    @PostMapping("/remove")
    public FavoriteActionResponse remove(@RequestParam("path") String path) throws IOException {
        favoriteService.remove(path);
        return FavoriteActionResponse.removed(
                FlashNotification.success("Removed from favorites."),
                path
        );
    }

    @PostMapping("/move")
    public ActionResponse move(
            @RequestParam("path") String path,
            @RequestParam("direction") String direction
    ) throws IOException {
        favoriteService.move(path, direction);
        return ActionResponse.ok(FlashNotification.success("Favorite order updated."));
    }

    private FavoriteActionResponse toggleResponse(String path, FavoriteItem favorite) throws IOException {
        FlashNotification notification = favorite == null
                ? FlashNotification.success("Removed from favorites.")
                : FlashNotification.success("Added to favorites.");
        return favorite == null
                ? FavoriteActionResponse.removed(notification, path)
                : FavoriteActionResponse.added(notification, favoritePayload(favorite));
    }

    private FavoritePayload favoritePayload(FavoriteItem favorite) throws IOException {
        return FavoritePayload.from(
                favorite,
                BookmarkLinkClickAction.from(nasProperties),
                favoriteService.hidden(favorite)
        );
    }

    private boolean showHiddenFavorites(String requestedHidden, HttpServletRequest request) {
        String hidden = requestedHidden == null
                ? BrowserPreferenceCookies.value(
                        request,
                        BrowserPreferenceCookies.FILES.hiddenCookie(),
                        this::normalizeHiddenMode
                )
                : normalizeHiddenMode(requestedHidden);
        return "show".equals(hidden);
    }

    private String normalizeHiddenMode(String hidden) {
        if (hidden == null || hidden.isBlank()) {
            return "hide";
        }
        return "show".equalsIgnoreCase(hidden) || "true".equalsIgnoreCase(hidden) ? "show" : "hide";
    }
}
