package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.notification.TelegramNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class LoginFailureAlertHandler implements AuthenticationFailureHandler {

    private final TelegramNotificationService telegramNotificationService;

    public LoginFailureAlertHandler(TelegramNotificationService telegramNotificationService) {
        this.telegramNotificationService = telegramNotificationService;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        telegramNotificationService.send(buildMessage(request, exception));
        response.sendRedirect("/login?error");
    }

    private String buildMessage(HttpServletRequest request, AuthenticationException exception) {
        return """
                NAS login failed
                user: %s
                ip: %s
                reason: %s
                """.formatted(
                usernameFrom(request),
                ClientIpResolver.resolve(request),
                reason(exception)
        );
    }

    private String usernameFrom(HttpServletRequest request) {
        String username = request.getParameter("username");
        return username == null || username.isBlank() ? "(unknown)" : username;
    }

    private String reason(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "bad credentials";
        }
        return exception.getMessage();
    }
}

