package io.github.fourilla.endervault.temporary;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class TemporaryArtifactRegistry {

    private final ConcurrentMap<Path, RegisteredArtifact> activeArtifacts = new ConcurrentHashMap<>();

    public Registration register(Path path, TemporaryArtifactType type, String ownerId) {
        Path normalizedPath = normalize(path);
        TemporaryArtifact artifact = new TemporaryArtifact(
                normalizedPath,
                Objects.requireNonNull(type, "type"),
                cleanOwnerId(ownerId),
                Instant.now()
        );
        RegisteredArtifact registered = new RegisteredArtifact(UUID.randomUUID(), artifact);
        RegisteredArtifact existing = activeArtifacts.putIfAbsent(normalizedPath, registered);
        if (existing != null) {
            throw new IllegalStateException("Temporary artifact is already active: " + normalizedPath.getFileName());
        }
        return new Registration(this, registered);
    }

    public boolean isActive(Path path) {
        return activeArtifacts.containsKey(normalize(path));
    }

    public Optional<TemporaryArtifact> find(Path path) {
        RegisteredArtifact registered = activeArtifacts.get(normalize(path));
        return registered == null ? Optional.empty() : Optional.of(registered.artifact());
    }

    public List<TemporaryArtifact> activeArtifacts() {
        return activeArtifacts.values().stream()
                .map(RegisteredArtifact::artifact)
                .sorted(Comparator.comparing(TemporaryArtifact::registeredAt))
                .toList();
    }

    private void release(RegisteredArtifact registered) {
        activeArtifacts.remove(registered.artifact().path(), registered);
    }

    private Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private String cleanOwnerId(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "-" : ownerId.trim();
    }

    private record RegisteredArtifact(UUID registrationId, TemporaryArtifact artifact) {
    }

    public static final class Registration implements AutoCloseable {

        private final TemporaryArtifactRegistry registry;
        private final RegisteredArtifact registered;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(TemporaryArtifactRegistry registry, RegisteredArtifact registered) {
            this.registry = registry;
            this.registered = registered;
        }

        public TemporaryArtifact artifact() {
            return registered.artifact();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registry.release(registered);
            }
        }
    }
}
