package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** The normal NIO implementation, separately injectable for provider/error policy tests. */
class NioFileSystem {

    void move(Path source, Path target, CopyOption... options) throws IOException {
        Files.move(source, target, options);
    }

    void syncFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    FileSystemOperations.SyncResult syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
            return FileSystemOperations.SyncResult.SYNCED;
        } catch (AccessDeniedException ex) {
            // The Windows NIO provider cannot open directory handles for FileChannel.force.
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                return FileSystemOperations.SyncResult.UNSUPPORTED;
            }
            throw ex;
        } catch (UnsupportedOperationException ex) {
            return FileSystemOperations.SyncResult.UNSUPPORTED;
        }
    }
}
