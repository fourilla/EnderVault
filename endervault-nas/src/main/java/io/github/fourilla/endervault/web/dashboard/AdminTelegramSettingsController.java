package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.notification.TelegramSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminTelegramSettingsController {

    private final TelegramSettingsService telegramSettingsService;

    public AdminTelegramSettingsController(TelegramSettingsService telegramSettingsService) {
        this.telegramSettingsService = telegramSettingsService;
    }

    @GetMapping("/files/telegram-alerts")
    public String telegramAlerts(Model model) {
        model.addAttribute("telegramSettings", telegramSettingsService.currentSettings());
        return "telegram-alerts";
    }

    @PostMapping("/files/telegram-alerts")
    public Object saveTelegramSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            telegramSettingsService.save(telegramSettingsService.updateFrom(parameters));
            FlashNotification notification = FlashNotification.success("Telegram alert settings saved.");
            if (wantsJson(request)) {
                return ResponseEntity.ok(ActionResponse.ok(notification));
            }
            FlashNotifications.success(redirectAttributes, notification.message());
        } catch (IllegalArgumentException ex) {
            if (wantsJson(request)) {
                return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
            }
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        } catch (IOException ex) {
            String message = "Telegram settings could not be saved.";
            if (wantsJson(request)) {
                return ResponseEntity.internalServerError().body(ActionResponse.error(message));
            }
            FlashNotifications.error(redirectAttributes, message);
        }
        return "redirect:/files/telegram-alerts";
    }

    @PostMapping("/files/telegram-alerts/test")
    public Object testTelegramSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            boolean sent = telegramSettingsService.sendTest(telegramSettingsService.updateFrom(parameters));
            FlashNotification notification;
            if (sent) {
                notification = FlashNotification.success("Telegram test message sent.");
            } else {
                notification = FlashNotification.error("Telegram test message failed.");
            }
            if (wantsJson(request)) {
                return sent
                        ? ResponseEntity.ok(ActionResponse.ok(notification))
                        : ResponseEntity.badRequest().body(ActionResponse.error(notification.message()));
            }
            addFlash(redirectAttributes, notification);
        } catch (IllegalArgumentException ex) {
            if (wantsJson(request)) {
                return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
            }
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return "redirect:/files/telegram-alerts";
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private void addFlash(RedirectAttributes redirectAttributes, FlashNotification notification) {
        if ("error".equals(notification.type())) {
            FlashNotifications.error(redirectAttributes, notification.message());
            return;
        }
        FlashNotifications.success(redirectAttributes, notification.message());
    }
}
