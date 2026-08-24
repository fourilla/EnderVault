package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.settings.SessionSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/sessions")
public class SessionSettingsApiController {

    private final SessionSettingsService sessionSettingsService;
    private final ActivityLogService activityLogService;

    public SessionSettingsApiController(
            SessionSettingsService sessionSettingsService,
            ActivityLogService activityLogService
    ) {
        this.sessionSettingsService = sessionSettingsService;
        this.activityLogService = activityLogService;
    }

    @PostMapping
    public ActionResponse save(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request
    ) throws IOException {
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
        return ActionResponse.ok(FlashNotification.success("Session settings saved and applied."));
    }
}
