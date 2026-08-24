package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.notification.TelegramSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminTelegramSettingsController {

    private final TelegramSettingsService telegramSettingsService;

    public AdminTelegramSettingsController(TelegramSettingsService telegramSettingsService) {
        this.telegramSettingsService = telegramSettingsService;
    }

    @GetMapping("/admin/settings/telegram-alerts")
    public String telegramAlerts(Model model) {
        model.addAttribute("telegramSettings", telegramSettingsService.currentSettings());
        return "telegram-alerts";
    }
}
