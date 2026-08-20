package io.github.fourilla.endervault.upload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ResumableUploadRepository {

    private static final TypeReference<List<ResumableUploadSession>> SESSION_LIST = new TypeReference<>() {
    };

    private final JsonRegistry<List<ResumableUploadSession>> registry;

    public ResumableUploadRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("resumable-uploads.json"),
                SESSION_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<ResumableUploadSession> list() throws IOException {
        return List.copyOf(registry.read());
    }

    public synchronized Optional<ResumableUploadSession> find(String id) throws IOException {
        return registry.read().stream().filter(session -> session.id().equals(id)).findFirst();
    }

    public synchronized void save(ResumableUploadSession session) throws IOException {
        List<ResumableUploadSession> sessions = new ArrayList<>(registry.read());
        sessions.removeIf(existing -> existing.id().equals(session.id()));
        sessions.add(session);
        registry.write(List.copyOf(sessions));
    }

    public synchronized void remove(String id) throws IOException {
        List<ResumableUploadSession> sessions = new ArrayList<>(registry.read());
        if (sessions.removeIf(session -> session.id().equals(id))) {
            registry.write(List.copyOf(sessions));
        }
    }
}
