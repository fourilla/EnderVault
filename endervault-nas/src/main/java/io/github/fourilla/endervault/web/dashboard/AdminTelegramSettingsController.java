package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.notification.TelegramSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
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
            return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/files/telegram-alerts");
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    "redirect:/files/telegram-alerts"
            );
        } catch (IOException ex) {
            String message = "Telegram settings could not be saved.";
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error(message),
                    "redirect:/files/telegram-alerts"
            );
        }
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
            return sent
                    ? ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/files/telegram-alerts")
                    : ActionResponseSupport.badRequest(request, redirectAttributes, notification, "redirect:/files/telegram-alerts");
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    "redirect:/files/telegram-alerts"
            );
        }
    }
}
