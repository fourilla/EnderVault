package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.notification.TelegramNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class LoginSuccessAlertHandler implements AuthenticationSuccessHandler {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final TelegramNotificationService telegramNotificationService;

    public LoginSuccessAlertHandler(TelegramNotificationService telegramNotificationService) {
        this.telegramNotificationService = telegramNotificationService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        telegramNotificationService.send(buildMessage(request, authentication));
        response.sendRedirect("/files");
    }

    private String buildMessage(HttpServletRequest request, Authentication authentication) {
        return """
                NAS login succeeded
                user: %s
                time: %s
                ip: %s
                """.formatted(
                mask(authentication.getName()),
                ZonedDateTime.now(ZoneId.systemDefault()).format(TIME_FORMATTER),
                ClientIpResolver.resolve(request)
        );
    }

    private String mask(String username) {
        if (username == null || username.length() <= 3) {
            return username;
        }
        return username.substring(0, 3) + "*".repeat(username.length() - 3);
    }
}

