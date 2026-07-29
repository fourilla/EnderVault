package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.settings.VpnSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.VpnStatusView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    @PostMapping("/admin/settings/vpn")
    public Object saveVpnSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            VpnProxyHealth health = vpnSettingsService.save(vpnSettingsService.updateFrom(parameters));
            FlashNotification notification = FlashNotification.success("VPN egress settings saved.");
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    redirectToVpnSettings(),
                    VpnSettingsResponse.ok(notification, VpnStatusView.from(health))
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToVpnSettings()
            );
        } catch (IOException ex) {
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error("VPN egress settings could not be saved."),
                    redirectToVpnSettings()
            );
        }
    }

    @PostMapping("/admin/settings/vpn/refresh")
    public Object refreshVpnHealth(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        VpnStatusView status = VpnStatusView.from(vpnSettingsService.refreshHealth());
        FlashNotification notification = FlashNotification.success("VPN health refreshed.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToVpnSettings(),
                VpnSettingsResponse.ok(notification, status)
        );
    }

    private String redirectToVpnSettings() {
        return "redirect:/admin/settings/vpn";
    }
}
