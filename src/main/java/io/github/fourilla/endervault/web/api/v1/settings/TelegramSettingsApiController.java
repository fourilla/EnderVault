package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.notification.TelegramSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/telegram-alerts")
public class TelegramSettingsApiController {

    private final TelegramSettingsService telegramSettingsService;

    public TelegramSettingsApiController(TelegramSettingsService telegramSettingsService) {
        this.telegramSettingsService = telegramSettingsService;
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        telegramSettingsService.save(telegramSettingsService.updateFrom(parameters));
        return ActionResponse.ok(FlashNotification.success("Telegram alert settings saved and applied."));
    }

    @PostMapping("/test")
    public ResponseEntity<ActionResponse> test(@RequestParam MultiValueMap<String, String> parameters) {
        boolean sent = telegramSettingsService.sendTest(telegramSettingsService.updateFrom(parameters));
        if (sent) {
            return ResponseEntity.ok(ActionResponse.ok(FlashNotification.success("Telegram test message sent.")));
        }
        return ResponseEntity.badRequest().body(ActionResponse.error("Telegram test message failed."));
    }
}
