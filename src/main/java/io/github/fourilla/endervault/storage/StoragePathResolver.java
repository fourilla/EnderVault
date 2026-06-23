package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

final class StoragePathResolver {

    private final Path root;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final Path uploadTempRoot;

    StoragePathResolver(Path root, Path trashRoot, Path metadataRoot, Path uploadTempRoot) {
        this.root = root;
        this.trashRoot = trashRoot;
        this.metadataRoot = metadataRoot;
        this.uploadTempRoot = uploadTempRoot;
    }

    Path resolveDirectory(StorageScope scope, String requestedPath) throws IOException {
        Path directory = resolve(scope, requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }
        return directory;
    }

    Path resolveChild(StorageScope scope, String directoryPath, String itemName, boolean mustExist)
            throws IOException {
        validateSingleName(itemName);
        Path directory = resolveDirectory(scope, directoryPath);
        Path child = directory.resolve(itemName).normalize();
        ensureInsideBase(scope, child);
        rejectHiddenSystemPath(scope, child);

        if (mustExist) {
            ensureExistingPathInsideBase(scope, child);
        } else {
            ensureParentInsideBase(scope, child);
        }
        return child;
    }

    Path resolve(StorageScope scope, String requestedPath) throws IOException {
        Path base = baseFor(scope);
        Path relative = sanitizeRelativePath(requestedPath);
        Path candidate = base.resolve(relative).normalize();
        ensureInsideBase(scope, candidate);
        ensureExistingPathInsideBase(scope, candidate);
        rejectHiddenSystemPath(scope, candidate);
        return candidate;
    }

    Path resolveSharedPath(Path sharedBase, String requestedPath) throws IOException {
        Path relative = sanitizeRelativePath(requestedPath);
        Path candidate = sharedBase.resolve(relative).normalize();
        ensureInsideSharedBase(sharedBase, candidate);
        ensureExistingPathInsideSharedBase(sharedBase, candidate);
        rejectVaultSystemPath(candidate);
        return candidate;
    }

    Path resolveTrashChild(String trashName) {
        validateSingleName(trashName);
        Path child = trashRoot.resolve(trashName).normalize();
        ensureInsideTrashRoot(child);
        return child;
    }

    Path resolveRestoreTargetPath(String vaultPath) throws IOException {
        validateVaultItemPath(vaultPath);
        Path target = root.resolve(sanitizeRelativePath(vaultPath)).normalize();
        ensureInsideBase(StorageScope.VAULT, target);
        rejectHiddenSystemPath(StorageScope.VAULT, target);
        ensureParentInsideBase(StorageScope.VAULT, target);
        return target;
    }

    Path baseFor(StorageScope scope) {
        return switch (scope) {
            case VAULT -> root;
        };
    }

    void validateSingleName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new StorageAccessException("Name is required.");
        }
        if (itemName.contains("/") || itemName.contains("\\") || ".".equals(itemName) || "..".equals(itemName)
                || itemName.contains(":")) {
            throw new StorageAccessException("Invalid name: " + itemName);
        }
    }

    void validateVaultItemPath(String vaultPath) {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            throw new StorageAccessException("Path is required.");
        }
    }

    boolean isHiddenSystemPath(StorageScope scope, Path path) {
        return scope == StorageScope.VAULT && isVaultSystemPath(path);
    }

    boolean isVaultSystemPath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(trashRoot) || normalizedPath.startsWith(metadataRoot);
    }

    void rejectHiddenSystemPath(StorageScope scope, Path path) throws IOException {
        if (isHiddenSystemPath(scope, path) || isRealVaultSystemPath(path)) {
            throw new NoSuchFileException(toRelativePath(baseFor(scope), path));
        }
    }

    void rejectVaultSystemPath(Path path) throws IOException {
        if (isVaultSystemPath(path) || isRealVaultSystemPath(path)) {
            throw new NoSuchFileException(toRelativePath(root, path));
        }
    }

    void ensureInsideBase(StorageScope scope, Path candidate) {
        Path base = baseFor(scope);
        if (!candidate.normalize().startsWith(base)) {
            throw new StorageAccessException("Path is outside the allowed storage area.");
        }
    }

    void ensureExistingPathInsideBase(StorageScope scope, Path candidate) throws IOException {
        Path base = baseFor(scope).toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(base)) {
            throw new StorageAccessException("Path is outside the allowed storage area.");
        }
    }

    void ensureParentInsideBase(StorageScope scope, Path candidate) throws IOException {
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new StorageAccessException("Invalid target path.");
        }
        ensureExistingPathInsideBase(scope, parent);
    }

    void ensureInsideSharedBase(Path sharedBase, Path candidate) {
        if (!candidate.normalize().startsWith(sharedBase)) {
            throw new StorageAccessException("Path is outside the shared directory.");
        }
    }

    void ensureExistingPathInsideSharedBase(Path sharedBase, Path candidate) throws IOException {
        Path realSharedBase = sharedBase.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realSharedBase)) {
            throw new StorageAccessException("Path is outside the shared directory.");
        }
    }

    void ensureInsideUploadTempRoot(Path candidate) {
        if (!candidate.normalize().startsWith(uploadTempRoot)) {
            throw new StorageAccessException("Path is outside upload temporary storage.");
        }
    }

    String toRelativePath(Path base, Path path) {
        Path relative = base.relativize(path);
        return relative.toString().replace('\\', '/');
    }

    private Path sanitizeRelativePath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank() || "/".equals(requestedPath)) {
            return Path.of("");
        }

        String cleaned = requestedPath.replace('\\', '/');
        Path relative = Path.of(cleaned).normalize();
        if (relative.isAbsolute()) {
            throw new StorageAccessException("Absolute paths are not allowed.");
        }
        for (Path segment : relative) {
            String value = segment.toString();
            if (value.isBlank() || ".".equals(value) || "..".equals(value) || value.contains(":")) {
                throw new StorageAccessException("Invalid path segment: " + value);
            }
        }
        return relative;
    }

    private void ensureInsideTrashRoot(Path candidate) {
        if (!candidate.normalize().startsWith(trashRoot)) {
            throw new StorageAccessException("Path is outside trash.");
        }
    }

    private boolean isRealVaultSystemPath(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return isVaultSystemPath(path.toRealPath());
    }
}
