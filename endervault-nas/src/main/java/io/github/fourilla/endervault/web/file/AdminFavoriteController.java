package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.BookmarkLinkClickAction;
import io.github.fourilla.endervault.web.support.FavoriteActionResponse;
import io.github.fourilla.endervault.web.support.FavoritePayload;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminFavoriteController {

    private final FavoriteService favoriteService;
    private final NasProperties nasProperties;

    public AdminFavoriteController(FavoriteService favoriteService, NasProperties nasProperties) {
        this.favoriteService = favoriteService;
        this.nasProperties = nasProperties;
    }

    @GetMapping("/files/favorites")
    public String favorites(Model model) throws IOException {
        model.addAttribute("favoriteItems", favoriteService.list());
        return "favorites";
    }

    @PostMapping("/files/favorites/toggle")
    public Object toggle(
            @RequestParam("path") String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FavoriteItem favorite = favoriteService.toggle(path);
        FlashNotification notification = favorite == null
                ? FlashNotification.success("Removed from favorites.")
                : FlashNotification.success("Added to favorites.");
        Object jsonBody = favorite == null
                ? FavoriteActionResponse.removed(notification, path)
                : FavoriteActionResponse.added(notification, favoritePayload(favorite));
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectBack(request, "redirect:/files/favorites"),
                jsonBody
        );
    }

    @PostMapping("/files/favorites/toggle-bookmark")
    public Object toggleBookmark(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FavoriteItem favorite = favoriteService.toggleBookmark(id);
        String path = "bookmark:" + (id == null ? "" : id.trim());
        FlashNotification notification = favorite == null
                ? FlashNotification.success("Removed from favorites.")
                : FlashNotification.success("Added to favorites.");
        Object jsonBody = favorite == null
                ? FavoriteActionResponse.removed(notification, path)
                : FavoriteActionResponse.added(notification, favoritePayload(favorite));
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectBack(request, "redirect:/files/favorites"),
                jsonBody
        );
    }

    @PostMapping("/files/favorites/remove")
    public Object remove(
            @RequestParam("path") String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        favoriteService.remove(path);
        FlashNotification notification = FlashNotification.success("Removed from favorites.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectBack(request, "redirect:/files/favorites"),
                FavoriteActionResponse.removed(notification, path)
        );
    }

    @PostMapping("/files/favorites/move")
    public Object move(
            @RequestParam("path") String path,
            @RequestParam("direction") String direction,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        favoriteService.move(path, direction);
        FlashNotification notification = FlashNotification.success("Favorite order updated.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectBack(request, "redirect:/files/favorites")
        );
    }

    private String redirectBack(HttpServletRequest request, String fallback) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return fallback;
        }

        try {
            URI uri = URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return fallback;
            }
            String query = uri.getRawQuery();
            return "redirect:" + path + (query == null ? "" : "?" + query);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private FavoritePayload favoritePayload(FavoriteItem favorite) {
        return FavoritePayload.from(favorite, BookmarkLinkClickAction.from(nasProperties));
    }
}
