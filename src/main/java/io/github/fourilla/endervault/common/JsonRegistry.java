package io.github.fourilla.endervault.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

public class JsonRegistry<T> {

    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final Path registryFile;
    private final TypeReference<T> typeReference;
    private final Supplier<T> defaultValueSupplier;
    private final CorruptionPolicy corruptionPolicy;

    public JsonRegistry(
            ObjectMapper objectMapper,
            Path registryFile,
            TypeReference<T> typeReference,
            Supplier<T> defaultValueSupplier,
            CorruptionPolicy corruptionPolicy
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registryFile = Objects.requireNonNull(registryFile, "registryFile").toAbsolutePath().normalize();
        this.typeReference = Objects.requireNonNull(typeReference, "typeReference");
        this.defaultValueSupplier = Objects.requireNonNull(defaultValueSupplier, "defaultValueSupplier");
        this.corruptionPolicy = Objects.requireNonNull(corruptionPolicy, "corruptionPolicy");
    }

    public void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        cleanupTempFiles();
        if (!Files.exists(registryFile)) {
            write(defaultValue());
        }
    }

    public T read() throws IOException {
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return defaultValue();
        }
        try {
            return objectMapper.readValue(registryFile.toFile(), typeReference);
        } catch (JsonProcessingException ex) {
            Path backupFile = backupCorruptFile(corruptionPolicy == CorruptionPolicy.BACKUP_AND_RESET);
            if (corruptionPolicy == CorruptionPolicy.BACKUP_AND_RESET) {
                T defaultValue = defaultValue();
                write(defaultValue);
                return defaultValue;
            }
            throw new IOException("Invalid JSON registry " + registryFile + "; backed up to " + backupFile, ex);
        }
    }

    public void write(T value) throws IOException {
        Files.createDirectories(registryFile.getParent());
        cleanupTempFiles();
        Path tempFile = Files.createTempFile(
                registryFile.getParent(),
                registryFile.getFileName().toString() + "-",
                ".tmp"
        );
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            moveReplacing(tempFile, registryFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public Path path() {
        return registryFile;
    }

    private T defaultValue() {
        return defaultValueSupplier.get();
    }

    private Path backupCorruptFile(boolean removeOriginal) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path backupFile = registryFile.resolveSibling(
                registryFile.getFileName()
                        + ".corrupt-"
                        + BACKUP_TIMESTAMP_FORMATTER.format(Instant.now())
                        + ".bak"
        );
        if (removeOriginal) {
            moveReplacing(registryFile, backupFile);
        } else {
            Files.copy(registryFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return backupFile;
    }

    private void cleanupTempFiles() throws IOException {
        Path parent = registryFile.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }

        String fileName = registryFile.getFileName().toString();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path tempFile : stream) {
                String tempFileName = tempFile.getFileName().toString();
                if (tempFileName.startsWith(fileName)
                        && tempFileName.endsWith(".tmp")
                        && Files.isRegularFile(tempFile)) {
                    Files.deleteIfExists(tempFile);
                }
            }
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public enum CorruptionPolicy {
        BACKUP_AND_RESET,
        BACKUP_AND_THROW
    }
}
