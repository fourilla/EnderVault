package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.VpnSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminVpnSettingsController {

    private final VpnSettingsService vpnSettingsService;

    public AdminVpnSettingsController(VpnSettingsService vpnSettingsService) {
        this.vpnSettingsService = vpnSettingsService;
    }

    @GetMapping("/admin/settings/vpn")
    public String vpnSettings(Model model) {
        model.addAttribute("settings", vpnSettingsService.currentSettings());
        return "vpn-settings";
    }
}
