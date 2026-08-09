package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RemoteDownloadRequestTicketService {

    private static final Duration RETENTION = Duration.ofMinutes(10);
    private static final int MAX_PENDING_PER_SESSION = 20;

    private final Map<String, PendingRequest> requests = new ConcurrentHashMap<>();

    public String issue(
            RemoteDownloadRequestSpec requestSpec,
            RemoteDownloadProbe probe,
            HttpServletRequest request
    ) {
        cleanupExpired();
        RequestOwner owner = owner(request);
        long pendingCount = requests.values().stream()
                .filter(pending -> pending.owner().equals(owner))
                .count();
        if (pendingCount >= MAX_PENDING_PER_SESSION) {
            throw new StorageAccessException("Too many pending remote download inspections.");
        }
        String id = UUID.randomUUID().toString();
        requests.put(id, new PendingRequest(
                owner,
                new RemoteDownloadPreparedRequest(requestSpec, probe),
                Instant.now().plus(RETENTION)
        ));
        return id;
    }

    public RemoteDownloadPreparedRequest consume(String rawId, HttpServletRequest request) {
        String id = cleanId(rawId);
        PendingRequest pending = requests.get(id);
        if (pending == null || pending.expired()) {
            if (pending != null) {
                requests.remove(id, pending);
            }
            throw new StorageAccessException("Remote download inspection was not found or expired.");
        }
        if (!pending.owner().equals(owner(request))) {
            throw new StorageAccessException("Remote download inspection belongs to another session.");
        }
        if (!requests.remove(id, pending)) {
            throw new StorageAccessException("Remote download inspection was already used.");
        }
        return pending.preparedRequest();
    }

    @Scheduled(fixedDelay = 60_000L)
    public void cleanupExpired() {
        requests.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    int pendingCount() {
        cleanupExpired();
        return requests.size();
    }

    private RequestOwner owner(HttpServletRequest request) {
        if (request == null) {
            throw new StorageAccessException("Authenticated session is required.");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new StorageAccessException("Authenticated session is required.");
        }
        Principal principal = request.getUserPrincipal();
        String actor = principal == null ? "anonymous" : principal.getName();
        return new RequestOwner(session.getId(), actor);
    }

    private String cleanId(String rawId) {
        String id = rawId == null ? "" : rawId.trim();
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Remote download request identifier is invalid.");
        }
    }

    private record RequestOwner(String sessionId, String actor) {
    }

    private record PendingRequest(
            RequestOwner owner,
            RemoteDownloadPreparedRequest preparedRequest,
            Instant expiresAt
    ) {
        private boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }
    }
}
