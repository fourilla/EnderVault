package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.AdvancedSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminAdvancedSettingsController {

    private final AdvancedSettingsService advancedSettingsService;

    public AdminAdvancedSettingsController(AdvancedSettingsService advancedSettingsService) {
        this.advancedSettingsService = advancedSettingsService;
    }

    @GetMapping("/admin/settings/advanced")
    public String advancedSettings(Model model) {
        model.addAttribute("settings", advancedSettingsService.currentSettings());
        return "advanced-settings";
    }
}
