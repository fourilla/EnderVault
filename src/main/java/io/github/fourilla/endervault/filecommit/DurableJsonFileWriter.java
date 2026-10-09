package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class DurableJsonFileWriter {

    private final ObjectMapper objectMapper;
    private final FileSystemOperations fileSystem;

    public DurableJsonFileWriter(ObjectMapper objectMapper) {
        this(objectMapper, FileSystemOperations.systemDefault());
    }

    public DurableJsonFileWriter(ObjectMapper objectMapper, FileSystemOperations fileSystem) {
        this.objectMapper = objectMapper;
        this.fileSystem = java.util.Objects.requireNonNull(fileSystem, "fileSystem");
    }

    <T> T read(Path path, Class<T> type) throws IOException {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (JacksonException ex) {
            throw new IOException("Failed to read JSON file " + path, ex);
        }
    }

    public void write(Path target, Object value) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        byte[] content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new IOException("Failed to serialize JSON file " + target, ex);
        }
        Path temporary = parent.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            fileSystem.replace(temporary, target, FileSystemOperations.Atomicity.PREFERRED);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void forceDirectory(Path directory) throws IOException {
        fileSystem.syncDirectory(directory);
    }
}
