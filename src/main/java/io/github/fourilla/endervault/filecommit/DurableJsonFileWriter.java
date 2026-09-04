package io.github.fourilla.endervault.filecommit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class DurableJsonFileWriter {

    private final ObjectMapper objectMapper;

    DurableJsonFileWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    <T> T read(Path path, Class<T> type) throws IOException {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (JacksonException ex) {
            throw new IOException("Failed to read JSON file " + path, ex);
        }
    }

    void write(Path target, Object value) throws IOException {
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
            moveReplacing(temporary, target);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ex) {
            // Windows does not generally allow opening a directory as a FileChannel.
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
