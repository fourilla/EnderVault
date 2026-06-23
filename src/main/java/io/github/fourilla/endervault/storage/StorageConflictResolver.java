package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class StorageConflictResolver {

    private final NasProperties nasProperties;
    private final StoragePathResolver pathResolver;

    StorageConflictResolver(NasProperties nasProperties, StoragePathResolver pathResolver) {
        this.nasProperties = nasProperties;
        this.pathResolver = pathResolver;
    }

    ConflictPolicy defaultPolicy() {
        return ConflictPolicy.from(nasProperties.getStorage().getDefaultConflictPolicy());
    }

    StorageConflictTarget resolve(Path requestedTarget, boolean sourceDirectory, ConflictPolicy policy)
            throws IOException {
        if (!Files.exists(requestedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return new StorageConflictTarget(requestedTarget, false);
        }

        ConflictPolicy effectivePolicy = effectivePolicy(policy);
        return switch (effectivePolicy) {
            case CANCEL -> throw new FileAlreadyExistsException(requestedTarget.getFileName().toString());
            case RENAME -> new StorageConflictTarget(nextAvailableTarget(requestedTarget, sourceDirectory), false);
            case OVERWRITE -> {
                validateOverwriteTarget(requestedTarget, sourceDirectory);
                yield new StorageConflictTarget(requestedTarget, true);
            }
        };
    }

    StorageConflictTarget resolveRestoreTarget(String vaultPath, boolean sourceDirectory, ConflictPolicy policy)
            throws IOException {
        Path target = pathResolver.resolveRestoreTargetPath(vaultPath);
        return resolve(target, sourceDirectory, policy);
    }

    private ConflictPolicy effectivePolicy(ConflictPolicy policy) {
        ConflictPolicy effective = policy == null ? defaultPolicy() : policy;
        return effective == null ? ConflictPolicy.CANCEL : effective;
    }

    private Path nextAvailableTarget(Path requestedTarget, boolean directory) throws IOException {
        String filename = requestedTarget.getFileName().toString();
        int extensionIndex = directory ? -1 : filename.lastIndexOf('.');
        String stem = extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
        String extension = extensionIndex > 0 ? filename.substring(extensionIndex) : "";
        Path parent = requestedTarget.getParent();
        for (int counter = 1; counter <= 9999; counter++) {
            Path candidate = parent.resolve(stem + " - " + counter + extension).normalize();
            pathResolver.ensureInsideBase(StorageScope.VAULT, candidate);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new FileAlreadyExistsException(requestedTarget.getFileName().toString());
    }

    private void validateOverwriteTarget(Path target, boolean sourceDirectory) {
        if (sourceDirectory || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Directory overwrite is not supported yet.");
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Only regular files can be overwritten.");
        }
    }

    record StorageConflictTarget(Path path, boolean overwrite) {
    }
}
