package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ArchiveStagingCommitter {

    private final Path root;
    private final Path archiveTempRoot;
    private final StoragePathResolver pathResolver;
    private final StorageConflictResolver conflictResolver;
    private final StorageTreeOperations treeOperations;
    private final StorageListingService listingService;
    private final Object commitMonitor = new Object();

    ArchiveStagingCommitter(
            Path root,
            Path archiveTempRoot,
            StoragePathResolver pathResolver,
            StorageConflictResolver conflictResolver,
            StorageTreeOperations treeOperations,
            StorageListingService listingService
    ) {
        this.root = root;
        this.archiveTempRoot = archiveTempRoot;
        this.pathResolver = pathResolver;
        this.conflictResolver = conflictResolver;
        this.treeOperations = treeOperations;
        this.listingService = listingService;
    }

    Path createWorkspace() throws IOException {
        Files.createDirectories(archiveTempRoot);
        return Files.createTempDirectory(archiveTempRoot, "extract-").toAbsolutePath().normalize();
    }

    FileItem commitDirectory(
            Path temporaryDirectory,
            String directoryPath,
            String directoryName,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Path source = requireTemporaryDirectory(temporaryDirectory);
        synchronized (commitMonitor) {
            Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, directoryName, false);
            StorageConflictResolver.StorageConflictTarget resolvedTarget =
                    conflictResolver.resolve(target, true, conflictPolicy);
            treeOperations.move(source, resolvedTarget.path(), false);
            return listingService.describeVaultPath(pathResolver.toRelativePath(root, resolvedTarget.path()));
        }
    }

    void preflight(
            String directoryPath,
            boolean createContainingDirectory,
            String directoryName,
            List<StorageBatchEntry> topLevelEntries,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        synchronized (commitMonitor) {
            if (createContainingDirectory) {
                pathResolver.validateSingleName(directoryName);
                Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, directoryName, false);
                conflictResolver.resolve(target, true, conflictPolicy);
                return;
            }
            planBatchTargets(directoryPath, topLevelEntries, conflictPolicy, null);
        }
    }

    StorageBatchCommitResult commitContents(
            Path temporaryDirectory,
            String directoryPath,
            List<StorageBatchEntry> topLevelEntries,
            ConflictPolicy conflictPolicy,
            StorageProgressListener progressListener
    ) throws IOException {
        Path sourceRoot = requireTemporaryDirectory(temporaryDirectory);
        StorageProgressListener progress = progressListener == null
                ? StorageProgressListener.NOOP
                : progressListener;
        synchronized (commitMonitor) {
            List<PlannedBatchEntry> planned = planBatchTargets(
                    directoryPath,
                    topLevelEntries,
                    conflictPolicy,
                    sourceRoot
            );
            verifyStagingTopLevelEntries(sourceRoot, planned);
            List<MovedBatchEntry> moved = new ArrayList<>();
            try {
                for (PlannedBatchEntry entry : planned) {
                    progress.checkCanceled();
                    TreeFingerprint fingerprint = planned.size() > 1
                            ? fingerprint(entry.source(), progress)
                            : null;
                    treeOperations.move(entry.source(), entry.target(), false);
                    moved.add(new MovedBatchEntry(entry.source(), entry.target(), fingerprint));
                }
            } catch (IOException | RuntimeException failure) {
                List<String> unresolved = rollbackBatchMoves(moved);
                if (!unresolved.isEmpty()) {
                    throw new PartialStorageCommitException(unresolved, failure);
                }
                throw failure;
            }
            return new StorageBatchCommitResult(planned.stream()
                    .map(entry -> pathResolver.toRelativePath(root, entry.target()))
                    .toList());
        }
    }

    void deleteWorkspace(Path workspace) throws IOException {
        Path safeWorkspace = requireTemporaryPath(workspace);
        if (Files.exists(safeWorkspace, LinkOption.NOFOLLOW_LINKS)) {
            treeOperations.deleteRecursively(safeWorkspace);
        }
    }

    private List<PlannedBatchEntry> planBatchTargets(
            String directoryPath,
            List<StorageBatchEntry> entries,
            ConflictPolicy conflictPolicy,
            Path sourceRoot
    ) throws IOException {
        List<StorageBatchEntry> safeEntries = entries == null ? List.of() : List.copyOf(entries);
        if (safeEntries.isEmpty()) {
            throw new StorageAccessException("An empty archive cannot be extracted directly.");
        }
        if (conflictResolver.effectivePolicy(conflictPolicy) == ConflictPolicy.OVERWRITE) {
            throw new StorageAccessException("Direct archive extraction supports cancel or rename only.");
        }

        Set<Path> reservedTargets = new HashSet<>();
        List<PlannedBatchEntry> planned = new ArrayList<>(safeEntries.size());
        for (StorageBatchEntry entry : safeEntries) {
            if (entry == null) {
                throw new StorageAccessException("Archive contains an invalid top-level entry.");
            }
            pathResolver.validateSingleName(entry.name());
            Path requestedTarget = pathResolver.resolveChild(
                    StorageScope.VAULT,
                    directoryPath,
                    entry.name(),
                    false
            );
            StorageConflictResolver.StorageConflictTarget resolved = conflictResolver.resolve(
                    requestedTarget,
                    entry.directory(),
                    conflictPolicy,
                    reservedTargets
            );
            reservedTargets.add(resolved.path());
            Path source = sourceRoot == null ? null : sourceRoot.resolve(entry.name()).normalize();
            if (source != null && !source.startsWith(sourceRoot)) {
                throw new StorageAccessException("Archive staging entry escapes its workspace.");
            }
            planned.add(new PlannedBatchEntry(entry.name(), entry.directory(), source, resolved.path()));
        }
        return List.copyOf(planned);
    }

    private void verifyStagingTopLevelEntries(Path sourceRoot, List<PlannedBatchEntry> planned) throws IOException {
        Map<String, PlannedBatchEntry> expected = new LinkedHashMap<>();
        for (PlannedBatchEntry entry : planned) {
            if (expected.put(entry.name(), entry) != null) {
                throw new StorageAccessException("Archive contains duplicate top-level entries.");
            }
            if (Files.isSymbolicLink(entry.source())) {
                throw new StorageAccessException("Symbolic links cannot be committed from archive staging.");
            }
            boolean validType = entry.directory()
                    ? Files.isDirectory(entry.source(), LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(entry.source(), LinkOption.NOFOLLOW_LINKS);
            if (!validType) {
                throw new StorageAccessException("Archive staging content does not match its manifest.");
            }
        }
        Set<String> actual;
        try (Stream<Path> children = Files.list(sourceRoot)) {
            actual = children.map(child -> child.getFileName().toString()).collect(Collectors.toSet());
        }
        if (!actual.equals(expected.keySet())) {
            throw new StorageAccessException("Archive staging content does not match its manifest.");
        }
    }

    private List<String> rollbackBatchMoves(List<MovedBatchEntry> moved) {
        List<String> unresolved = new ArrayList<>();
        for (int index = moved.size() - 1; index >= 0; index--) {
            MovedBatchEntry entry = moved.get(index);
            try {
                if (Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
                        || !Files.exists(entry.target(), LinkOption.NOFOLLOW_LINKS)
                        || entry.fingerprint() == null
                        || !entry.fingerprint().equals(fingerprint(entry.target(), StorageProgressListener.NOOP))) {
                    unresolved.add(pathResolver.toRelativePath(root, entry.target()));
                    continue;
                }
                treeOperations.move(entry.target(), entry.source(), false);
            } catch (IOException | RuntimeException rollbackFailure) {
                unresolved.add(pathResolver.toRelativePath(root, entry.target()));
            }
        }
        return List.copyOf(unresolved);
    }

    private TreeFingerprint fingerprint(Path treeRoot, StorageProgressListener progressListener) throws IOException {
        StorageProgressListener progress = progressListener == null
                ? StorageProgressListener.NOOP
                : progressListener;
        Map<String, TreeEntryIdentity> entries = new TreeMap<>();
        Files.walkFileTree(treeRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                add(directory, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                add(file, attributes);
                return FileVisitResult.CONTINUE;
            }

            private void add(Path path, BasicFileAttributes attributes) throws IOException {
                progress.checkCanceled();
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
                    throw new StorageAccessException("Symbolic links cannot be committed from archive staging.");
                }
                String relative = treeRoot.relativize(path).toString().replace('\\', '/');
                entries.put(relative, new TreeEntryIdentity(
                        attributes.isDirectory(),
                        attributes.isRegularFile(),
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                        attributes.fileKey()
                ));
            }
        });
        return new TreeFingerprint(entries);
    }

    private Path requireTemporaryDirectory(Path path) throws IOException {
        Path safePath = requireTemporaryPath(path);
        if (!Files.isDirectory(safePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(safePath)) {
            throw new StorageAccessException("Archive staging source is not a regular directory.");
        }
        return safePath;
    }

    private Path requireTemporaryPath(Path path) {
        if (path == null) {
            throw new StorageAccessException("Archive staging path is missing.");
        }
        Path safePath = path.toAbsolutePath().normalize();
        if (!safePath.startsWith(archiveTempRoot) || safePath.equals(archiveTempRoot)) {
            throw new StorageAccessException("Archive staging path is outside the managed temporary directory.");
        }
        return safePath;
    }

    private record PlannedBatchEntry(String name, boolean directory, Path source, Path target) {
    }

    private record MovedBatchEntry(Path source, Path target, TreeFingerprint fingerprint) {
    }

    private record TreeFingerprint(Map<String, TreeEntryIdentity> entries) {
        private TreeFingerprint {
            entries = Map.copyOf(entries);
        }
    }

    private record TreeEntryIdentity(
            boolean directory,
            boolean regularFile,
            long size,
            long lastModifiedMillis,
            Object fileKey
    ) {
    }
}
