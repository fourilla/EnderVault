package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.web.support.ViteAssetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminFavoriteController {

    private final ViteAssetService viteAssetService;

    public AdminFavoriteController(ViteAssetService viteAssetService) {
        this.viteAssetService = viteAssetService;
    }

    @GetMapping("/files/favorites")
    public String favorites(Model model) {
        model.addAttribute("favoritesFrontend", viteAssetService.entry("src/favorites/main.tsx"));
        return "favorites";
    }
}
