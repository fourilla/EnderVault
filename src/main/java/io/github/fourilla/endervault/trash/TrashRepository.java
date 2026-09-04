package io.github.fourilla.endervault.trash;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class TrashRepository {

    private static final TypeReference<List<TrashRecord>> TRASH_RECORD_LIST = new TypeReference<>() {
    };

    private final JsonRegistry<List<TrashRecord>> registry;

    public TrashRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("trash-records.json"),
                TRASH_RECORD_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<TrashRecord> list() throws IOException {
        return readAllMutable().stream()
                .sorted(Comparator.comparing(TrashRecord::deletedAt).reversed())
                .toList();
    }

    public synchronized Optional<TrashRecord> find(String id) throws IOException {
        return readAllMutable().stream()
                .filter(record -> record.id().equals(id))
                .findFirst();
    }

    public synchronized void add(TrashRecord record) throws IOException {
        List<TrashRecord> records = readAllMutable();
        records.add(record);
        writeAll(records);
    }

    public synchronized void remove(String id) throws IOException {
        List<TrashRecord> records = readAllMutable();
        if (records.removeIf(record -> record.id().equals(id))) {
            writeAll(records);
        }
    }

    public synchronized void removeAll(Collection<String> ids) throws IOException {
        if (ids.isEmpty()) {
            return;
        }

        List<TrashRecord> records = readAllMutable();
        if (records.removeIf(record -> ids.contains(record.id()))) {
            writeAll(records);
        }
    }

    public synchronized void clear() throws IOException {
        writeAll(List.of());
    }

    private List<TrashRecord> readAllMutable() throws IOException {
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<TrashRecord> records) throws IOException {
        registry.write(List.copyOf(records));
    }
}
