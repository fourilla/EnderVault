package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.GeneralSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminGeneralSettingsController {

    private final GeneralSettingsService generalSettingsService;

    public AdminGeneralSettingsController(GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    @GetMapping("/admin/settings/general")
    public String generalSettings(Model model) {
        model.addAttribute("settings", generalSettingsService.currentSettings());
        return "general-settings";
    }
}
