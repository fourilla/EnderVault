package io.github.fourilla.endervault.web.support;

import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

@Service
public class AdminSpaViewService {

    private static final String ADMIN_APP_ENTRY = "src/app/main.tsx";
    private static final String ADMIN_APP_TEMPLATE = "admin-app";

    private final ViteAssetService viteAssetService;

    public AdminSpaViewService(ViteAssetService viteAssetService) {
        this.viteAssetService = viteAssetService;
    }

    public String render(Model model) {
        model.addAttribute("adminAppFrontend", viteAssetService.entry(ADMIN_APP_ENTRY));
        return ADMIN_APP_TEMPLATE;
    }
}
