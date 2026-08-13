package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ArchivePathPolicy {

    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_DEPTH = 128;

    private ArchivePathPolicy() {
    }

    static String normalizeEntry(String rawName) {
        if (rawName == null || rawName.isBlank() || rawName.indexOf('\0') >= 0) {
            throw new StorageAccessException("Archive entry has an invalid path.");
        }
        String value = rawName.replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank() || value.length() > MAX_PATH_LENGTH || value.startsWith("/") || value.contains(":")) {
            throw new StorageAccessException("Archive entry has an unsafe path.");
        }

        String[] rawSegments = value.split("/", -1);
        if (rawSegments.length > MAX_DEPTH) {
            throw new StorageAccessException("Archive entry path is too deeply nested.");
        }
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new StorageAccessException("Archive entry has an unsafe path segment.");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    static String normalizeParent(String rawParent) {
        if (rawParent == null || rawParent.isBlank() || "/".equals(rawParent)) {
            return "";
        }
        return normalizeEntry(rawParent);
    }

    static Path resolveDestination(Path outputRoot, String entryPath) {
        try {
            Path target = outputRoot.resolve(entryPath.replace('/', java.io.File.separatorChar)).normalize();
            if (!target.startsWith(outputRoot)) {
                throw new StorageAccessException("Archive entry escapes the extraction directory.");
            }
            return target;
        } catch (InvalidPathException ex) {
            throw new StorageAccessException("Archive entry path is not supported by this operating system.", ex);
        }
    }

    static String parent(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    static String name(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }
}
