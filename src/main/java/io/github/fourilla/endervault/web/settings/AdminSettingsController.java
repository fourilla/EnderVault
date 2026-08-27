package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.web.support.ViteAssetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSettingsController {

    private final ViteAssetService viteAssetService;

    public AdminSettingsController(ViteAssetService viteAssetService) {
        this.viteAssetService = viteAssetService;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        model.addAttribute("settingsFrontend", viteAssetService.entry("src/settings/main.tsx"));
        return "settings";
    }
}
