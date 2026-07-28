package io.github.fourilla.endervault.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

public class ManagedSessionAuthenticationStrategy implements SessionAuthenticationStrategy {

    private final SessionManagementService sessionManagementService;

    public ManagedSessionAuthenticationStrategy(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    @Override
    public void onAuthentication(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String authMethod = "passkey".equals(authentication.getCredentials()) ? "passkey" : "password";
        sessionManagementService.registerAuthenticatedSession(request, authentication, authMethod);
    }
}
