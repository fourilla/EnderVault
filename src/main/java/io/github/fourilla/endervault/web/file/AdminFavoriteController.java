package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminFavoriteController {

    private final FavoriteService favoriteService;

    public AdminFavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/files/favorites")
    public String favorites(HttpServletRequest request, Model model) throws IOException {
        model.addAttribute("favoriteItems", favoriteService.listDisplay(showHiddenFavorites(request)));
        return "favorites";
    }

    private boolean showHiddenFavorites(HttpServletRequest request) {
        String requestedHidden = request.getParameter("hidden");
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
