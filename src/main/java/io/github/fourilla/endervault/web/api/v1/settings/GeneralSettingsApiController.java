package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.settings.GeneralSettingsService;
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
@RequestMapping("/api/v1/settings/general")
public class GeneralSettingsApiController {

    private final GeneralSettingsService generalSettingsService;

    public GeneralSettingsApiController(GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    @GetMapping
    public GeneralSettingsService.GeneralSettingsSnapshot current() {
        return generalSettingsService.currentSettings();
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        GeneralSettingsService.GeneralSettingsUpdate update = generalSettingsService.updateFrom(parameters);
        boolean restartRequired = generalSettingsService.requiresRestart(update);
        generalSettingsService.save(update);
        String message = restartRequired
                ? "Settings saved. Restart EnderVault to apply fields marked Restart required."
                : "Settings saved and applied.";
        return ActionResponse.ok(FlashNotification.success(message));
    }
}
