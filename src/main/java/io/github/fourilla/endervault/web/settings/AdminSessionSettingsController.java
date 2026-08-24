package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.SessionSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSessionSettingsController {

    private final SessionSettingsService sessionSettingsService;

    public AdminSessionSettingsController(SessionSettingsService sessionSettingsService) {
        this.sessionSettingsService = sessionSettingsService;
    }

    @GetMapping("/admin/settings/sessions")
    public String sessionSettings(Model model) {
        model.addAttribute("sessionSettings", sessionSettingsService.currentSettings());
        return "session-settings";
    }
}
