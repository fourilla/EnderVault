package io.github.fourilla.endervault.trash;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private final ObjectMapper objectMapper;
    private final Path registryFile;

    public TrashRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.objectMapper = objectMapper;
        this.registryFile = nasProperties.getStorage().getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(nasProperties.getStorage().getMetadataDirectory())
                .resolve("trash-records.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        if (!Files.exists(registryFile)) {
            writeAll(List.of());
        }
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
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(registryFile.toFile(), TRASH_RECORD_LIST));
    }

    private void writeAll(List<TrashRecord> records) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), records);
        try {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
