package io.github.fourilla.endervault.filerequest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class FileRequestRepository {

    private static final TypeReference<List<FileRequest>> REQUEST_LIST = new TypeReference<>() {
    };

    private final JsonRegistry<List<FileRequest>> registry;

    public FileRequestRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("file-requests.json"),
                REQUEST_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<FileRequest> list() throws IOException {
        return List.copyOf(registry.read());
    }

    public synchronized void write(List<FileRequest> requests) throws IOException {
        registry.write(List.copyOf(new ArrayList<>(requests)));
    }
}
