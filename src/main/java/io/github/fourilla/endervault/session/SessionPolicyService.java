package io.github.fourilla.endervault.session;

import io.github.fourilla.endervault.config.NasProperties;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class SessionPolicyService {

    private final NasProperties nasProperties;

    public SessionPolicyService(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public int maximumSessionsForSecurity() {
        int configured = nasProperties.getSecurity().getMaxConcurrentSessions();
        return configured == 0 ? -1 : configured;
    }

    public int configuredMaximumSessions() {
        return nasProperties.getSecurity().getMaxConcurrentSessions();
    }

    public int idleTimeoutMinutes() {
        return nasProperties.getSecurity().getSessionIdleTimeoutMinutes();
    }

    public Duration idleTimeout() {
        return Duration.ofMinutes(idleTimeoutMinutes());
    }

    public boolean idleTimeoutUnlimited() {
        return idleTimeoutMinutes() == 0;
    }

    public int servletIdleTimeoutSeconds() {
        if (idleTimeoutUnlimited()) {
            return -1;
        }
        return Math.toIntExact(idleTimeout().toSeconds());
    }

    public void applyIdleTimeout(HttpSession session) {
        if (session != null) {
            session.setMaxInactiveInterval(servletIdleTimeoutSeconds());
        }
    }
}
