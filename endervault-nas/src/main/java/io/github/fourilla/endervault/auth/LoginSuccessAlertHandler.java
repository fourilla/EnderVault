package io.github.fourilla.endervault.auth;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.notification.TelegramNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class LoginSuccessAlertHandler implements AuthenticationSuccessHandler {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final ActivityLogService activityLogService;
    private final TelegramNotificationService telegramNotificationService;

    public LoginSuccessAlertHandler(
            ActivityLogService activityLogService,
            TelegramNotificationService telegramNotificationService
    ) {
        this.activityLogService = activityLogService;
        this.telegramNotificationService = telegramNotificationService;
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
                ClientIpResolver.resolve(request),
                null,
                null,
                true,
                "Login succeeded for " + username,
                Map.of("username", username)
        );
        telegramNotificationService.send(buildMessage(request, authentication));
        response.sendRedirect("/files");
    }

    private String usernameFrom(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "(unknown)";
        }
        return authentication.getName();
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
