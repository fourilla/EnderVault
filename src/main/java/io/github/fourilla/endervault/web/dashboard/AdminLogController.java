package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.web.support.ViteAssetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminLogController {

    private final ViteAssetService viteAssetService;

    public AdminLogController(ViteAssetService viteAssetService) {
        this.viteAssetService = viteAssetService;
    }

    @GetMapping("/admin/logs")
    public String logs(Model model) {
        model.addAttribute("activityLogsFrontend", viteAssetService.entry("src/activity-logs/main.tsx"));
        return "logs";
    }
}
