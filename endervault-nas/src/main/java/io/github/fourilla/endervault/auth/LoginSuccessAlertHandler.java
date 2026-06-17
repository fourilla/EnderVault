package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.activity.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class LoginSuccessAlertHandler implements AuthenticationSuccessHandler {

    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;

    public LoginSuccessAlertHandler(ActivityLogService activityLogService, ClientIpResolver clientIpResolver) {
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        String username = usernameFrom(authentication);
        activityLogService.record(
                "LOGIN_SUCCESS",
                username,
                clientIpResolver.resolve(request),
                null,
                null,
                true,
                "Login succeeded for " + username,
                Map.of("username", username, "authMethod", "password")
        );
        response.sendRedirect("/files");
    }

    private String usernameFrom(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "(unknown)";
        }
        return authentication.getName();
    }
}
