package io.github.fourilla.endervault.filetool.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TextDraftRepository {

    private static final TypeReference<List<TextDraftRecord>> DRAFT_LIST = new TypeReference<>() {
    };
    private static final String DRAFT_EXTENSION = ".draft";

    private final JsonRegistry<List<TextDraftRecord>> registry;
    private final Path contentRoot;
    private final NasProperties.FileTools fileTools;

    public TextDraftRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        Path storageRoot = nasProperties.getStorage().getRoot()
                .toAbsolutePath()
                .normalize();
        Path metadataRoot = storageRoot
                .resolve(nasProperties.getStorage().getMetadataDirectory())
                .normalize();
        if (!metadataRoot.startsWith(storageRoot)) {
            throw new StorageAccessException("Metadata directory must stay inside storage.");
        }
        Path draftRoot = metadataRoot.resolve("drafts").resolve("text").normalize();
        if (!draftRoot.startsWith(metadataRoot)) {
            throw new StorageAccessException("Text draft directory must stay inside metadata storage.");
        }
        this.registry = new JsonRegistry<>(
                objectMapper,
                draftRoot.resolve("registry.json"),
                DRAFT_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
        this.contentRoot = draftRoot.resolve("content");
        this.fileTools = nasProperties.getFileTools();
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
        Files.createDirectories(contentRoot);
        cleanupTemporaryContentFiles();
    }

    public synchronized List<TextDraftRecord> records() throws IOException {
        return List.copyOf(registry.read());
    }

    public synchronized Optional<TextDraftRecord> findByVaultPath(String vaultPath) throws IOException {
        return registry.read().stream()
                .filter(record -> record.vaultPath().equals(vaultPath))
                .findFirst();
    }

    public synchronized Optional<TextDraftRecord> findById(UUID id) throws IOException {
        return registry.read().stream()
                .filter(record -> record.id().equals(id))
                .findFirst();
    }

    public synchronized void save(TextDraftRecord record, byte[] content) throws IOException {
        writeContent(record.id(), content);
        List<TextDraftRecord> records = new ArrayList<>(registry.read());
        records.removeIf(existing -> existing.id().equals(record.id())
                || existing.vaultPath().equals(record.vaultPath()));
        records.add(record);
        registry.write(List.copyOf(records));
    }

    public synchronized String readContent(UUID id) throws IOException {
        Path path = contentPath(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new StorageAccessException("Text draft content is missing or invalid.");
        }
        if (Files.size(path) > maxContentBytes()) {
            throw new StorageAccessException("Text draft content exceeds the configured text limit.");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public synchronized boolean contentExists(UUID id) {
        Path path = contentPath(id);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    public synchronized void delete(UUID id) throws IOException {
        deleteMetadata(id);
        Files.deleteIfExists(contentPath(id));
    }

    public synchronized void deleteMetadata(UUID id) throws IOException {
        List<TextDraftRecord> records = new ArrayList<>(registry.read());
        if (records.removeIf(record -> record.id().equals(id))) {
            registry.write(List.copyOf(records));
        }
    }

    public synchronized void deleteContentFile(String fileName) throws IOException {
        Files.deleteIfExists(requireContentFile(fileName));
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<TextDraftRecord> records = new ArrayList<>(registry.read());
        boolean changed = false;
        for (int index = 0; index < records.size(); index++) {
            TextDraftRecord record = records.get(index);
            if (matchesPathOrDescendant(record.vaultPath(), oldPath)) {
                records.set(index, record.withVaultPath(rebasedPath(record.vaultPath(), oldPath, newPath)));
                changed = true;
            }
        }
        if (changed) {
            registry.write(List.copyOf(records));
        }
    }

    public synchronized List<TextDraftContentFile> contentFiles() throws IOException {
        if (!Files.isDirectory(contentRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(contentRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(DRAFT_EXTENSION))
                    .map(this::contentFile)
                    .toList();
        }
    }

    public String contentFileName(UUID id) {
        return id + DRAFT_EXTENSION;
    }

    private void writeContent(UUID id, byte[] content) throws IOException {
        Files.createDirectories(contentRoot);
        Path target = contentPath(id);
        Path temporary = Files.createTempFile(contentRoot, id + "-", ".tmp");
        try {
            Files.write(temporary, content);
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path contentPath(UUID id) {
        return contentRoot.resolve(contentFileName(id)).normalize();
    }

    private Path requireContentFile(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (!normalized.endsWith(DRAFT_EXTENSION)) {
            throw new StorageAccessException("Text draft content file name is invalid.");
        }
        String identifier = normalized.substring(0, normalized.length() - DRAFT_EXTENSION.length());
        UUID id;
        try {
            id = UUID.fromString(identifier);
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Text draft content file name is invalid.", ex);
        }
        if (!normalized.equals(contentFileName(id))) {
            throw new StorageAccessException("Text draft content file name is invalid.");
        }
        Path path = contentRoot.resolve(normalized).normalize();
        if (!path.startsWith(contentRoot)) {
            throw new StorageAccessException("Text draft content path is invalid.");
        }
        return path;
    }

    private long maxContentBytes() {
        return Math.max(
                1024L,
                Math.max(fileTools.getTextAutoLoadMaxBytes(), fileTools.getTextManualLoadMaxBytes())
        );
    }

    private TextDraftContentFile contentFile(Path path) {
        try {
            return new TextDraftContentFile(
                    path.getFileName().toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant()
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to inspect text draft content.", ex);
        }
    }

    private void cleanupTemporaryContentFiles() throws IOException {
        if (!Files.isDirectory(contentRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.list(contentRoot)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private boolean matchesPathOrDescendant(String candidatePath, String basePath) {
        return candidatePath.equals(basePath) || candidatePath.startsWith(basePath + "/");
    }

    private String rebasedPath(String candidatePath, String oldPath, String newPath) {
        if (candidatePath.equals(oldPath)) {
            return newPath;
        }
        return newPath + candidatePath.substring(oldPath.length());
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
