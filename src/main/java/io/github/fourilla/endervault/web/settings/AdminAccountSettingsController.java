package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.AccountSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminAccountSettingsController {

    private final AccountSettingsService accountSettingsService;

    public AdminAccountSettingsController(AccountSettingsService accountSettingsService) {
        this.accountSettingsService = accountSettingsService;
    }

    @GetMapping("/admin/settings/account")
    public String accountSettings(Model model) {
        model.addAttribute("accountSettings", accountSettingsService.currentSettings());
        return "account-settings";
    }
}
