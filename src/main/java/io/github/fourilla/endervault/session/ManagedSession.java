package io.github.fourilla.endervault.session;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;

record ManagedSession(
        String managementId,
        String sessionId,
        String username,
        String ip,
        String userAgent,
        String deviceLabel,
        String authMethod,
        Instant createdAt,
        HttpSession session
) {
}
