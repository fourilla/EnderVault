package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.web.support.FavoriteActionResponse;
import io.github.fourilla.endervault.web.support.FavoritePayload;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminFavoriteController {

    private final FavoriteService favoriteService;

    public AdminFavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
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

        if (wantsJson(request)) {
            return favorite == null
                    ? ResponseEntity.ok(FavoriteActionResponse.removed(notification, path))
                    : ResponseEntity.ok(FavoriteActionResponse.added(notification, FavoritePayload.from(favorite)));
        }

        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectBack(request, "redirect:/files/favorites");
    }

    @PostMapping("/files/favorites/remove")
    public Object remove(
            @RequestParam("path") String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        favoriteService.remove(path);
        FlashNotification notification = FlashNotification.success("Removed from favorites.");

        if (wantsJson(request)) {
            return ResponseEntity.ok(FavoriteActionResponse.removed(notification, path));
        }

        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectBack(request, "redirect:/files/favorites");
    }

    @PostMapping("/files/favorites/move")
    public String move(
            @RequestParam("path") String path,
            @RequestParam("direction") String direction,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        favoriteService.move(path, direction);
        FlashNotifications.success(redirectAttributes, "Favorite order updated.");
        return "redirect:/files/favorites";
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
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
}
