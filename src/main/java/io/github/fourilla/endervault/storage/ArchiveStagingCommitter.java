package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ArchiveStagingCommitter {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path root;
    private final Path archiveTempRoot;
    private final StoragePathResolver pathResolver;
    private final StorageConflictResolver conflictResolver;
    private final StorageTreeOperations treeOperations;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;
    private final Object commitMonitor = new Object();

    ArchiveStagingCommitter(
            Path root,
            Path archiveTempRoot,
            StoragePathResolver pathResolver,
            StorageConflictResolver conflictResolver,
            StorageTreeOperations treeOperations,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.root = root;
        this.archiveTempRoot = archiveTempRoot;
        this.pathResolver = pathResolver;
        this.conflictResolver = conflictResolver;
        this.treeOperations = treeOperations;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    Path createWorkspace(String prefix) throws IOException {
        Files.createDirectories(archiveTempRoot);
        return Files.createTempDirectory(archiveTempRoot, prefix).toAbsolutePath().normalize();
    }

    Path requireCreationOutput(Path outputFile) {
        Path output = requireTemporaryPath(outputFile);
        Path workspace = output.getParent();
        if (workspace == null
                || !java.util.Objects.equals(workspace.getParent(), archiveTempRoot)
                || !workspace.getFileName().toString().startsWith("create-")
                || !output.getFileName().toString().equals("archive.zip")
                || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(output)) {
            throw new StorageAccessException("Archive creation output is not a managed regular file.");
        }
        return output;
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

    ArchiveCommitPlan planCommit(
            Path temporaryDirectory,
            String directoryPath,
            boolean createContainingDirectory,
            String directoryName,
            List<StorageBatchEntry> topLevelEntries,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Path sourceRoot = requireTemporaryDirectory(temporaryDirectory);
        Path workspace = requireExtractionWorkspace(sourceRoot);
        synchronized (commitMonitor) {
            if (createContainingDirectory) {
                pathResolver.validateSingleName(directoryName);
                Path target = pathResolver.resolveChild(
                        StorageScope.VAULT, directoryPath, directoryName, false
                );
                StorageConflictResolver.StorageConflictTarget resolved =
                        conflictResolver.resolve(target, true, conflictPolicy);
                return new ArchiveCommitPlan(
                        workspace,
                        List.of(new ArchiveCommitPlanItem(
                                sourceRoot,
                                pathResolver.toRelativePath(root, resolved.path()),
                                true
                        ))
                );
            }

            List<PlannedBatchEntry> planned = planBatchTargets(
                    directoryPath,
                    topLevelEntries,
                    conflictPolicy,
                    sourceRoot
            );
            verifyStagingTopLevelEntries(sourceRoot, planned);
            return new ArchiveCommitPlan(
                    workspace,
                    planned.stream()
                            .map(entry -> new ArchiveCommitPlanItem(
                                    entry.source(),
                                    pathResolver.toRelativePath(root, entry.target()),
                                    entry.directory()
                            ))
                            .toList()
            );
        }
    }

    String storageRelativeCommitPath(Path path) throws IOException {
        Path safePath = requireArchiveCommitPath(path, true);
        return pathResolver.toRelativePath(root, safePath);
    }

    Path resolveCommitPath(String storageRelativePath) throws IOException {
        if (storageRelativePath == null || storageRelativePath.isBlank()) {
            throw new StorageAccessException("Archive staging commit path is required.");
        }
        Path candidate = root.resolve(storageRelativePath.replace('\\', '/')).normalize();
        return requireArchiveCommitPath(candidate, false);
    }

    Path workspaceForCommitPath(Path path) throws IOException {
        Path safePath = requireArchiveCommitPath(path, false);
        Path relative = archiveTempRoot.relativize(safePath);
        return archiveTempRoot.resolve(relative.getName(0)).normalize();
    }

    void deleteWorkspace(Path workspace) throws IOException {
        Path safeWorkspace = requireTemporaryPath(workspace);
        if (Files.exists(safeWorkspace, LinkOption.NOFOLLOW_LINKS)) {
            treeOperations.deleteRecursively(safeWorkspace);
        }
    }

    List<StorageService.ArchiveStagingInfo> listArtifacts(StorageProgressListener progressListener)
            throws IOException {
        StorageProgressListener progress = progressListener == null
                ? StorageProgressListener.NOOP
                : progressListener;
        Files.createDirectories(archiveTempRoot);
        List<Path> children;
        try (Stream<Path> stream = Files.list(archiveTempRoot)) {
            children = stream.toList();
        }
        List<StorageService.ArchiveStagingInfo> artifacts = new ArrayList<>(children.size());
        for (Path child : children) {
            progress.checkCanceled();
            artifacts.add(toArchiveStagingInfo(child, progress));
        }
        return artifacts.stream()
                .sorted(java.util.Comparator.comparing(StorageService.ArchiveStagingInfo::modifiedAt).reversed())
                .toList();
    }

    void deleteArtifact(String name) throws IOException {
        pathResolver.validateSingleName(name);
        Path target = requireTemporaryPath(archiveTempRoot.resolve(name));
        if (temporaryArtifactRegistry.isActive(target)) {
            throw new StorageAccessException("Archive staging workspace is still in use.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            treeOperations.deleteRecursively(target);
        }
    }

    private StorageService.ArchiveStagingInfo toArchiveStagingInfo(
            Path artifact,
            StorageProgressListener progress
    ) throws IOException {
        long size = 0L;
        Instant modifiedAt = Files.getLastModifiedTime(artifact, LinkOption.NOFOLLOW_LINKS).toInstant();
        if (!Files.isSymbolicLink(artifact)) {
            try (Stream<Path> paths = Files.walk(artifact)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    progress.checkCanceled();
                    BasicFileAttributes attributes = Files.readAttributes(
                            path,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    if (attributes.isRegularFile()) {
                        size = attributes.size() > Long.MAX_VALUE - size
                                ? Long.MAX_VALUE
                                : size + attributes.size();
                    }
                    if (attributes.lastModifiedTime().toInstant().isAfter(modifiedAt)) {
                        modifiedAt = attributes.lastModifiedTime().toInstant();
                    }
                }
            }
        }
        String activeOperation = temporaryArtifactRegistry.find(artifact)
                .map(active -> active.type().label())
                .orElse(null);
        String name = artifact.getFileName().toString();
        return new StorageService.ArchiveStagingInfo(
                name,
                archiveOperation(name),
                size,
                ByteSizeFormatter.humanSize(size),
                modifiedAt,
                MODIFIED_FORMATTER.format(modifiedAt),
                activeOperation != null,
                activeOperation
        );
    }

    private String archiveOperation(String name) {
        if (name.startsWith("create-")) {
            return "Archive creation";
        }
        if (name.startsWith("extract-")) {
            return "Archive extraction";
        }
        return "Unknown archive operation";
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

    private Path requireTemporaryDirectory(Path path) throws IOException {
        Path safePath = requireTemporaryPath(path);
        if (!Files.isDirectory(safePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(safePath)) {
            throw new StorageAccessException("Archive staging source is not a regular directory.");
        }
        return safePath;
    }

    private Path requireExtractionWorkspace(Path sourceRoot) {
        Path workspace = sourceRoot.getParent();
        if (workspace == null
                || !workspace.getParent().equals(archiveTempRoot)
                || !workspace.getFileName().toString().startsWith("extract-")
                || !sourceRoot.getFileName().toString().equals("content")) {
            throw new StorageAccessException("Archive extraction source is outside a managed workspace.");
        }
        return workspace;
    }

    private Path requireArchiveCommitPath(Path path, boolean mustExist) throws IOException {
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(archiveTempRoot) || candidate.equals(archiveTempRoot)) {
            throw new StorageAccessException("Archive commit path is outside archive staging.");
        }
        Path relative = archiveTempRoot.relativize(candidate);
        if (relative.getNameCount() < 2
                || relative.getNameCount() > 3
                || !relative.getName(0).toString().startsWith("extract-")
                || !relative.getName(1).toString().equals("content")) {
            throw new StorageAccessException("Archive commit path is not a top-level extraction item.");
        }
        Path workspace = archiveTempRoot.resolve(relative.getName(0)).normalize();
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace)) {
            throw new StorageAccessException("Archive extraction workspace is unavailable or unsafe.");
        }
        Path content = workspace.resolve("content");
        if (Files.exists(content, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(content))) {
            throw new StorageAccessException("Archive extraction content directory is unsafe.");
        }
        if (mustExist && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(candidate.toString());
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new StorageAccessException("Archive commit path cannot be a symbolic link.");
        }
        return candidate;
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

}
