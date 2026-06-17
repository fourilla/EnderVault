package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.activity.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class LoginFailureAlertHandler implements AuthenticationFailureHandler {

    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;

    public LoginFailureAlertHandler(ActivityLogService activityLogService, ClientIpResolver clientIpResolver) {
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        String username = usernameFrom(request);
        String reason = reason(exception);
        activityLogService.record(
                "LOGIN_FAILURE",
                username,
                clientIpResolver.resolve(request),
                null,
                null,
                false,
                "Login failed for " + username,
                Map.of("username", username, "reason", reason, "authMethod", "password")
        );
        response.sendRedirect("/login?error");
    }

    private String usernameFrom(HttpServletRequest request) {
        String username = request.getParameter("username");
        return username == null || username.isBlank() ? "(unknown)" : username;
    }

    private String reason(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "bad credentials";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "authentication failed" : message;
    }
}
