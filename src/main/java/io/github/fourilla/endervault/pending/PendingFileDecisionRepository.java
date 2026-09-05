package io.github.fourilla.endervault.pending;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PendingFileDecisionRepository {

    private static final TypeReference<List<PendingFileDecision>> DECISION_LIST = new TypeReference<>() {
    };

    private final JsonRegistry<List<PendingFileDecision>> registry;

    public PendingFileDecisionRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("pending-file-decisions.json"),
                DECISION_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<PendingFileDecision> list() throws IOException {
        return List.copyOf(registry.read());
    }

    public synchronized Optional<PendingFileDecision> find(String id) throws IOException {
        return registry.read().stream().filter(decision -> decision.id().equals(id)).findFirst();
    }

    public synchronized void add(PendingFileDecision decision) throws IOException {
        List<PendingFileDecision> decisions = new ArrayList<>(registry.read());
        decisions.add(decision);
        registry.write(List.copyOf(decisions));
    }

    public synchronized void remove(String id) throws IOException {
        List<PendingFileDecision> decisions = new ArrayList<>(registry.read());
        if (decisions.removeIf(decision -> decision.id().equals(id))) {
            registry.write(List.copyOf(decisions));
        }
    }
}
