package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.session.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSessionController {

    private final SessionManagementService sessionManagementService;

    public AdminSessionController(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    @GetMapping("/admin/sessions")
    public String sessions(HttpServletRequest request, Model model) {
        model.addAttribute(
                "sessions",
                sessionManagementService.listActive(currentSessionId(request))
        );
        return "sessions";
    }

    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? "" : session.getId();
    }
}
