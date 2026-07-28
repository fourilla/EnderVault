package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.settings.SessionSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminSessionSettingsController {

    private final SessionSettingsService sessionSettingsService;
    private final ActivityLogService activityLogService;

    public AdminSessionSettingsController(
            SessionSettingsService sessionSettingsService,
            ActivityLogService activityLogService
    ) {
        this.sessionSettingsService = sessionSettingsService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin/settings/sessions")
    public String sessionSettings(Model model) {
        model.addAttribute("sessionSettings", sessionSettingsService.currentSettings());
        return "session-settings";
    }

    @PostMapping("/admin/settings/sessions")
    public Object saveSessionSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            SessionSettingsService.SessionSettingsUpdate update = sessionSettingsService.updateFrom(parameters);
            int updatedSessions = sessionSettingsService.save(update);
            activityLogService.record(
                    "SESSION_POLICY_UPDATE",
                    request,
                    null,
                    null,
                    "Session policy updated.",
                    Map.of(
                            "maxConcurrentSessions", Integer.toString(update.maxConcurrentSessions()),
                            "idleTimeoutMinutes", Integer.toString(update.sessionIdleTimeoutMinutes()),
                            "activeSessionsUpdated", Integer.toString(updatedSessions)
                    )
            );
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    FlashNotification.success("Session settings saved and applied."),
                    redirectToSettings()
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToSettings()
            );
        } catch (IOException ex) {
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error("Session settings could not be saved."),
                    redirectToSettings()
            );
        }
    }

    private String redirectToSettings() {
        return "redirect:/admin/settings/sessions";
    }
}
