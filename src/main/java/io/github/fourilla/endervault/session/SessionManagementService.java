package io.github.fourilla.endervault.session;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Service;

@Service
public class SessionManagementService {

    private static final int MAX_USER_AGENT_LENGTH = 1024;

    private final SessionRegistry sessionRegistry;
    private final SessionPolicyService sessionPolicyService;
    private final ClientIpResolver clientIpResolver;
    private final Map<String, ManagedSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdByManagementId = new ConcurrentHashMap<>();

    public SessionManagementService(
            SessionRegistry sessionRegistry,
            SessionPolicyService sessionPolicyService,
            ClientIpResolver clientIpResolver
    ) {
        this.sessionRegistry = sessionRegistry;
        this.sessionPolicyService = sessionPolicyService;
        this.clientIpResolver = clientIpResolver;
    }

    public void registerAuthenticatedSession(
            HttpServletRequest request,
            Authentication authentication,
            String authMethod
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        sessionPolicyService.applyIdleTimeout(session);
        String sessionId = session.getId();
        ManagedSession previous = sessionsById.get(sessionId);
        String managementId = previous == null ? UUID.randomUUID().toString() : previous.managementId();
        String userAgent = cleanUserAgent(request.getHeader("User-Agent"));
        ManagedSession managed = new ManagedSession(
                managementId,
                sessionId,
                authentication == null ? "admin" : clean(authentication.getName(), "admin"),
                clientIpResolver.resolve(request),
                userAgent,
                SessionDeviceLabeler.label(userAgent),
                clean(authMethod, "password"),
                sessionCreationTime(session),
                session
        );

        sessionsById.put(sessionId, managed);
        sessionIdByManagementId.put(managementId, sessionId);
    }

    public List<SessionView> listActive(String currentSessionId) {
        List<SessionView> sessions = new ArrayList<>();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation information : sessionRegistry.getAllSessions(principal, false)) {
                ManagedSession managed = managedSession(information);
                Instant lastActive = information.getLastRequest().toInstant();
                Instant expiresAt = sessionPolicyService.idleTimeoutUnlimited()
                        ? null
                        : lastActive.plus(sessionPolicyService.idleTimeout());
                sessions.add(new SessionView(
                        managed.managementId(),
                        managed.username(),
                        managed.ip(),
                        managed.userAgent(),
                        managed.deviceLabel(),
                        managed.authMethod(),
                        managed.createdAt(),
                        lastActive,
                        expiresAt,
                        information.getSessionId().equals(currentSessionId)
                ));
            }
        }
        sessions.sort(Comparator
                .comparing(SessionView::current).reversed()
                .thenComparing(SessionView::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(sessions);
    }

    public int activeCount() {
        int count = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            count += sessionRegistry.getAllSessions(principal, false).size();
        }
        return count;
    }

    public RevokedSession revoke(String managementId, String currentSessionId) {
        String sessionId = sessionIdByManagementId.get(managementId);
        if (sessionId == null) {
            throw new IllegalArgumentException("The selected session is no longer active.");
        }

        ManagedSession managed = sessionsById.get(sessionId);
        SessionInformation information = sessionRegistry.getSessionInformation(sessionId);
        if (information == null || information.isExpired()) {
            remove(sessionId);
            throw new IllegalArgumentException("The selected session is no longer active.");
        }

        boolean current = sessionId.equals(currentSessionId);
        information.expireNow();
        invalidate(managed);
        remove(sessionId);
        return new RevokedSession(
                managed == null ? "admin" : managed.username(),
                managed == null ? "-" : managed.ip(),
                managed == null ? "Unknown device" : managed.deviceLabel(),
                current
        );
    }

    public int applyRuntimePolicy() {
        int updated = 0;
        for (ManagedSession managed : sessionsById.values()) {
            try {
                sessionPolicyService.applyIdleTimeout(managed.session());
                updated++;
            } catch (IllegalStateException ignored) {
                remove(managed.sessionId());
            }
        }
        return updated;
    }

    @EventListener
    public void sessionDestroyed(HttpSessionDestroyedEvent event) {
        remove(event.getId());
    }

    private ManagedSession managedSession(SessionInformation information) {
        return sessionsById.computeIfAbsent(information.getSessionId(), sessionId -> {
            String managementId = UUID.randomUUID().toString();
            sessionIdByManagementId.put(managementId, sessionId);
            return new ManagedSession(
                    managementId,
                    sessionId,
                    username(information.getPrincipal()),
                    "-",
                    "",
                    "Unknown device",
                    "password",
                    information.getLastRequest().toInstant(),
                    null
            );
        });
    }

    private void invalidate(ManagedSession managed) {
        if (managed == null || managed.session() == null) {
            return;
        }
        try {
            managed.session().invalidate();
        } catch (IllegalStateException ignored) {
            // Already invalidated by logout, timeout, or another management request.
        }
    }

    private void remove(String sessionId) {
        ManagedSession removed = sessionsById.remove(sessionId);
        if (removed != null) {
            sessionIdByManagementId.remove(removed.managementId(), sessionId);
        }
    }

    private Instant sessionCreationTime(HttpSession session) {
        try {
            return Instant.ofEpochMilli(session.getCreationTime());
        } catch (IllegalStateException ex) {
            return Instant.now();
        }
    }

    private String cleanUserAgent(String value) {
        String cleaned = value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() <= MAX_USER_AGENT_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_USER_AGENT_LENGTH);
    }

    private String clean(String value, String fallback) {
        String cleaned = value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private String username(Object principal) {
        if (principal instanceof UserDetails userDetails) {
            return clean(userDetails.getUsername(), "admin");
        }
        return principal == null ? "admin" : clean(principal.toString(), "admin");
    }

    public record RevokedSession(
            String username,
            String ip,
            String deviceLabel,
            boolean current
    ) {
    }
}
