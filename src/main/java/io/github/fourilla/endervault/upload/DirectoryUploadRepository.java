package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DirectoryUploadRepository {
    private final Path root;
    private final ObjectMapper mapper;
    private final DurableJsonFileWriter writer;

    public DirectoryUploadRepository(ObjectMapper mapper, NasProperties properties) {
        this.mapper = mapper;
        this.writer = new DurableJsonFileWriter(mapper);
        root = properties.getStorage().getRoot().toAbsolutePath().normalize()
                .resolve(properties.getStorage().getMetadataDirectory()).resolve("directory-uploads");
    }

    public synchronized DirectoryUpload require(String id) throws IOException {
        Path path = path(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Directory upload not found.");
        DirectoryUpload upload = registry(id).read();
        if (upload == null || !id.equals(upload.id())) throw new StorageAccessException("Invalid directory upload metadata.");
        return upload;
    }

    public synchronized List<String> ids() throws IOException {
        ensureRoot();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f-]{36}\\.json"))
                    .map(name -> name.substring(0, 36)).toList();
        }
    }

    public synchronized void save(DirectoryUpload upload) throws IOException { writer.write(path(upload.id()), upload); }
    void forceDirectory(Path directory) throws IOException { writer.forceDirectory(directory); }
    public synchronized void remove(String id) throws IOException { Files.deleteIfExists(path(id)); }

    private JsonRegistry<DirectoryUpload> registry(String id) throws IOException {
        return new JsonRegistry<>(mapper, path(id), new TypeReference<>() {}, () -> null,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW);
    }

    private Path path(String id) throws IOException {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new StorageAccessException("Invalid directory upload id.");
        ensureRoot();
        Path path = root.resolve(id + ".json");
        if (Files.isSymbolicLink(path)) throw new StorageAccessException("Directory upload metadata cannot be a symbolic link.");
        return path;
    }

    private void ensureRoot() throws IOException {
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(root.getParent())) {
            throw new StorageAccessException("Directory upload metadata cannot be a symbolic link.");
        }
        Files.createDirectories(root);
        writer.forceDirectory(root.getParent());
    }
}
