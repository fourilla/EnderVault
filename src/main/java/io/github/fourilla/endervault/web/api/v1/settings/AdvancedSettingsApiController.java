package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.settings.AdvancedSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/advanced")
public class AdvancedSettingsApiController {

    private final AdvancedSettingsService advancedSettingsService;

    public AdvancedSettingsApiController(AdvancedSettingsService advancedSettingsService) {
        this.advancedSettingsService = advancedSettingsService;
    }

    @GetMapping
    public AdvancedSettingsService.AdvancedSettingsSnapshot current() {
        return advancedSettingsService.currentSettings();
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        boolean restartRequired = advancedSettingsService.save(advancedSettingsService.updateFrom(parameters));
        String message = restartRequired
                ? "Advanced settings saved. Restart EnderVault to apply fields marked Restart required."
                : "Advanced settings saved and applied.";
        return ActionResponse.ok(FlashNotification.success(message));
    }
}
