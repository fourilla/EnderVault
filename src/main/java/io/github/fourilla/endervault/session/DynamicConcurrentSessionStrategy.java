package io.github.fourilla.endervault.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;

public class DynamicConcurrentSessionStrategy extends ConcurrentSessionControlAuthenticationStrategy {

    private final SessionRegistry sessionRegistry;
    private final SessionPolicyService sessionPolicyService;

    public DynamicConcurrentSessionStrategy(
            SessionRegistry sessionRegistry,
            SessionPolicyService sessionPolicyService
    ) {
        super(sessionRegistry);
        this.sessionRegistry = sessionRegistry;
        this.sessionPolicyService = sessionPolicyService;
        setExceptionIfMaximumExceeded(false);
    }

    @Override
    public void onAuthentication(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws SessionAuthenticationException {
        int maximumSessions = getMaximumSessionsForThisUser(authentication);
        if (maximumSessions == -1) {
            return;
        }

        List<SessionInformation> activeSessions = sessionRegistry.getAllPrincipals().stream()
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
                .collect(Collectors.toCollection(ArrayList::new));
        if (activeSessions.size() < maximumSessions) {
            return;
        }

        if (activeSessions.size() == maximumSessions && request.getSession(false) != null) {
            String currentSessionId = request.getSession(false).getId();
            boolean alreadyRegistered = activeSessions.stream()
                    .anyMatch(session -> session.getSessionId().equals(currentSessionId));
            if (alreadyRegistered) {
                return;
            }
        }

        allowableSessionsExceeded(activeSessions, maximumSessions, sessionRegistry);
    }

    @Override
    protected int getMaximumSessionsForThisUser(Authentication authentication) {
        return sessionPolicyService.maximumSessionsForSecurity();
    }
}
