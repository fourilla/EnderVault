package io.github.fourilla.endervault.directorytransfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

/** Bounded, non-mutating reads shared by merge metadata diagnostics. */
final class DirectoryTransferInspectionReader {
    private DirectoryTransferInspectionReader() {}

    static <T> T read(ObjectMapper mapper, Path path, Class<T> type, int limit) throws IOException {
        DirectoryTransferPlanner.rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular record");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Record exceeds inspection limit");
            T value = mapper.readValue(bytes, type);
            if (value == null) throw new IOException("Empty record");
            return value;
        }
    }
}
