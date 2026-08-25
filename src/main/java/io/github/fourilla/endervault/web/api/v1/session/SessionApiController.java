package io.github.fourilla.endervault.web.api.v1.session;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.session.SessionManagementService.RevokedSession;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

    private final SessionManagementService sessionManagementService;
    private final ActivityLogService activityLogService;

    public SessionApiController(
            SessionManagementService sessionManagementService,
            ActivityLogService activityLogService
    ) {
        this.sessionManagementService = sessionManagementService;
        this.activityLogService = activityLogService;
    }

    @PostMapping("/revoke")
    public ResponseEntity<ActionResponse> revoke(
            @RequestParam String managementId,
            HttpServletRequest request
    ) {
        try {
            RevokedSession revoked = sessionManagementService.revoke(
                    managementId,
                    currentSessionId(request)
            );
            activityLogService.record(
                    "SESSION_REVOKE",
                    request,
                    null,
                    null,
                    "An active session was revoked.",
                    Map.of(
                            "targetUsername", revoked.username(),
                            "targetIp", revoked.ip(),
                            "targetDevice", revoked.deviceLabel(),
                            "currentSession", Boolean.toString(revoked.current())
                    )
            );

            ActionResponse response = revoked.current()
                    ? ActionResponse.redirect(
                            FlashNotification.info("The current session was revoked."),
                            "/login?session-revoked"
                    )
                    : ActionResponse.ok(FlashNotification.success("Session revoked."));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
        }
    }

    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? "" : session.getId();
    }
}
