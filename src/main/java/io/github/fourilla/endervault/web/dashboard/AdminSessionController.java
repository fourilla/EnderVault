package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.session.SessionManagementService.RevokedSession;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminSessionController {

    private final SessionManagementService sessionManagementService;
    private final ActivityLogService activityLogService;

    public AdminSessionController(
            SessionManagementService sessionManagementService,
            ActivityLogService activityLogService
    ) {
        this.sessionManagementService = sessionManagementService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin/sessions")
    public String sessions(HttpServletRequest request, Model model) {
        model.addAttribute(
                "sessions",
                sessionManagementService.listActive(currentSessionId(request))
        );
        return "sessions";
    }

    @PostMapping("/admin/sessions/revoke")
    public Object revoke(
            @RequestParam String managementId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
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

            if (revoked.current()) {
                FlashNotification notification = FlashNotification.info("The current session was revoked.");
                if (ActionResponseSupport.wantsJson(request)) {
                    return ResponseEntity.ok(ActionResponse.redirect(notification, "/login?session-revoked"));
                }
                return "redirect:/login?session-revoked";
            }

            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    FlashNotification.success("Session revoked."),
                    "redirect:/admin/sessions"
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    "redirect:/admin/sessions"
            );
        }
    }

    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? "" : session.getId();
    }
}
