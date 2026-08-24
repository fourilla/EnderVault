package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.settings.VpnSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/vpn")
public class VpnSettingsApiController {

    private final VpnSettingsService vpnSettingsService;

    public VpnSettingsApiController(VpnSettingsService vpnSettingsService) {
        this.vpnSettingsService = vpnSettingsService;
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        vpnSettingsService.save(vpnSettingsService.updateFrom(parameters));
        return ActionResponse.ok(FlashNotification.success("VPN egress settings saved and applied."));
    }
}
