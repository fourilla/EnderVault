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
    private final ObjectMapper mapper;
    private final io.github.fourilla.endervault.filecommit.DurableJsonFileWriter writer;

    public PendingFileDecisionRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        mapper = objectMapper;
        writer = new io.github.fourilla.endervault.filecommit.DurableJsonFileWriter(objectMapper);
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

    synchronized MergeClaim mergeClaim(String id) throws IOException {
        var path = claimPath(id);
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        var claim = new JsonRegistry<MergeClaim>(mapper, path, new TypeReference<>() {}, () -> null,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (claim == null || !id.equals(claim.decision().id())) throw new IOException("Invalid pending merge claim.");
        return claim;
    }

    synchronized void claimMerge(PendingFileDecision decision, String mergeId) throws IOException {
        var next = new MergeClaim(mergeId, decision);
        var previous = mergeClaim(decision.id());
        if (previous != null) {
            if (!previous.equals(next)) throw new io.github.fourilla.endervault.common.StorageAccessException("Pending directory belongs to another merge.");
            return;
        }
        writer.write(claimPath(decision.id()), next);
        writer.forceDirectory(registry.path().getParent());
    }

    synchronized void releaseMerge(String id, String mergeId) throws IOException {
        var claim = mergeClaim(id);
        if (claim == null || !claim.mergeId().equals(mergeId)) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Pending merge owner changed.");
        }
        var path = claimPath(id);
        java.nio.file.Files.delete(path);
        writer.forceDirectory(path.getParent());
    }

    synchronized void transferMerge(String id, String expectedOwner, String nextOwner) throws IOException {
        var claim = mergeClaim(id);
        if (claim == null) throw new IOException("Pending merge claim is missing.");
        if (claim.mergeId().equals(nextOwner)) return;
        if (!claim.mergeId().equals(expectedOwner)) throw new IOException("Pending merge owner changed.");
        writer.write(claimPath(id), new MergeClaim(nextOwner, claim.decision()));
    }

    private java.nio.file.Path claimPath(String id) throws IOException {
        if (id == null || !java.util.UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid pending ID.");
        var path = registry.path().getParent().resolve("pending-directory-merges").resolve(id + ".json");
        for (var parent = path; parent != null; parent = parent.getParent()) {
            if (java.nio.file.Files.isSymbolicLink(parent)) throw new IOException("Pending merge claim path contains a symbolic link.");
        }
        return path;
    }

    public record MergeClaim(String mergeId, PendingFileDecision decision) {
        public MergeClaim {
            if (mergeId == null || !java.util.UUID.fromString(mergeId).toString().equals(mergeId)
                    || decision == null || !decision.directory()) throw new IllegalArgumentException("Invalid pending merge claim.");
        }
    }
}
