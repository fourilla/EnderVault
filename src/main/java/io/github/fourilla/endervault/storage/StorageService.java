package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StorageService {

    private final Path root;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final Path fileStagingRoot;
    private final Path archiveTempRoot;
    private final String trashDirectoryName;
    private final String metadataDirectoryName;
    private final StoragePathResolver pathResolver;
    private final StorageZipWriter zipWriter;
    private final FileStagingService fileStagingService;
    private final StorageListingService listingService;
    private final StorageConflictResolver conflictResolver;
    private final StorageTreeOperations treeOperations;
    private final ArchiveStagingCommitter archiveStagingCommitter;
    private final NasProperties.Share shareProperties;

    public StorageService(NasProperties nasProperties) {
        this(nasProperties, new FileActionRegistry(), new TemporaryArtifactRegistry());
    }

    @Autowired
    public StorageService(
            NasProperties nasProperties,
            FileActionRegistry fileActionRegistry,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        NasProperties.Storage storage = nasProperties.getStorage();
        this.root = storage.getRoot().toAbsolutePath().normalize();
        this.trashDirectoryName = validateConfiguredDirectory(storage.getTrashDirectory());
        this.metadataDirectoryName = validateConfiguredDirectory(storage.getMetadataDirectory());
        this.trashRoot = root.resolve(trashDirectoryName).normalize();
        this.metadataRoot = root.resolve(metadataDirectoryName).normalize();
        this.fileStagingRoot = metadataRoot.resolve("file-staging").normalize();
        this.archiveTempRoot = metadataRoot.resolve("archive-staging").normalize();
        this.pathResolver = new StoragePathResolver(root, trashRoot, metadataRoot, fileStagingRoot);
        this.zipWriter = new StorageZipWriter();
        this.fileStagingService = new FileStagingService(
                fileStagingRoot,
                pathResolver,
                temporaryArtifactRegistry
        );
        this.listingService = new StorageListingService(root, trashRoot, pathResolver, fileActionRegistry);
        this.conflictResolver = new StorageConflictResolver(nasProperties, pathResolver);
        this.treeOperations = new StorageTreeOperations(
                pathResolver,
                fileStagingService,
                temporaryArtifactRegistry
        );
        this.archiveStagingCommitter = new ArchiveStagingCommitter(
                root,
                archiveTempRoot,
                pathResolver,
                conflictResolver,
                treeOperations,
                listingService,
                temporaryArtifactRegistry
        );
        this.shareProperties = nasProperties.getShare();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(root);
        createSystemDirectory(trashRoot, "Trash");
        createSystemDirectory(metadataRoot, "Metadata");
        createSystemDirectory(fileStagingRoot, "File staging");
        createSystemDirectory(archiveTempRoot, "Archive temporary");
    }

    public DirectoryListing list(StorageScope scope, String requestedPath) throws IOException {
        return list(scope, requestedPath, FileSort.NAME, SortDirection.ASC);
    }

    public DirectoryListing list(
            StorageScope scope,
            String requestedPath,
            FileSort sort,
            SortDirection direction
    ) throws IOException {
        return listingService.list(scope, requestedPath, sort, direction);
    }

    public DirectoryListing list(
            StorageScope scope,
            String requestedPath,
            FileSort sort,
            SortDirection direction,
            boolean showHidden
    ) throws IOException {
        return listingService.list(scope, requestedPath, sort, direction, showHidden);
    }

    public DirectoryListing list(
            StorageScope scope,
            String requestedPath,
            FileSort sort,
            SortDirection direction,
            boolean showHidden,
            StorageEntryFilter entryFilter
    ) throws IOException {
        return listingService.list(scope, requestedPath, sort, direction, showHidden, entryFilter);
    }

    public DirectoryListing listTrash() throws IOException {
        return listingService.listTrash();
    }

    public StorageUsage storageUsage() {
        long totalBytes = root.toFile().getTotalSpace();
        long usableBytes = root.toFile().getUsableSpace();
        long usedBytes = Math.max(0L, totalBytes - usableBytes);
        int usedPercent = totalBytes <= 0L
                ? 0
                : (int) Math.min(100L, Math.round((double) usedBytes * 100.0 / (double) totalBytes));
        return new StorageUsage(
                usedBytes,
                totalBytes,
                usableBytes,
                ByteSizeFormatter.humanSize(usedBytes),
                ByteSizeFormatter.humanSize(totalBytes),
                ByteSizeFormatter.humanSize(usableBytes),
                usedPercent
        );
    }

    public List<FileItem> search(StorageScope scope, String requestedRoot, String query) throws IOException {
        return listingService.search(scope, requestedRoot, query);
    }

    public List<FileItem> search(StorageScope scope, String requestedRoot, String query, boolean showHidden)
            throws IOException {
        return listingService.search(scope, requestedRoot, query, showHidden);
    }

    public Path resolveFile(StorageScope scope, String directoryPath, String fileName) throws IOException {
        Path file = pathResolver.resolveChild(scope, directoryPath, fileName, true);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public Path resolveVaultFile(String vaultPath) throws IOException {
        Path file = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(vaultPath);
        }
        return file;
    }

    public Path resolveVaultPath(String vaultPath) throws IOException {
        return pathResolver.resolve(StorageScope.VAULT, vaultPath);
    }

    public Path resolveVaultDirectory(String vaultPath) throws IOException {
        Path directory = pathResolver.resolveDirectory(StorageScope.VAULT, vaultPath);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(vaultPath == null ? "" : vaultPath);
        }
        return directory;
    }

    public FileItem describeVaultPath(String vaultPath) throws IOException {
        return listingService.describeVaultPath(vaultPath);
    }

    public boolean vaultPathContainsHiddenElement(String vaultPath) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path target = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        return StorageHiddenPolicy.containsHiddenElement(root, target);
    }

    public FileDetail detail(StorageScope scope, String vaultPath) throws IOException {
        return listingService.detail(scope, vaultPath);
    }

    public FileItem describeVaultChild(String directoryPath, String itemName) throws IOException {
        return listingService.describeVaultChild(directoryPath, itemName);
    }

    public Path ensureVaultDirectory(String vaultPath) throws IOException {
        return pathResolver.resolveDirectory(StorageScope.VAULT, vaultPath);
    }

    public String normalizeVaultDirectory(String vaultPath) throws IOException {
        Path directory = pathResolver.resolveDirectory(StorageScope.VAULT, vaultPath);
        return pathResolver.toRelativePath(root, directory);
    }

    public String renameVaultPath(String vaultPath, String newName) throws IOException {
        return renameVaultPath(vaultPath, newName, null);
    }

    public String renameVaultPath(String vaultPath, String newName, ConflictPolicy conflictPolicy) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        Path target = source.resolveSibling(newName).normalize();
        pathResolver.validateSingleName(newName);
        pathResolver.ensureInsideBase(StorageScope.VAULT, target);
        pathResolver.ensureParentInsideBase(StorageScope.VAULT, target);
        if (source.equals(target)) {
            return pathResolver.toRelativePath(root, source);
        }
        StorageConflictResolver.StorageConflictTarget resolvedTarget = conflictResolver.resolve(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        treeOperations.move(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public String setHiddenVaultPath(String vaultPath, boolean hidden, ConflictPolicy conflictPolicy)
            throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        boolean currentlyHidden = StorageHiddenPolicy.isHidden(source);
        if (currentlyHidden == hidden) {
            return pathResolver.toRelativePath(root, source);
        }

        if (StorageHiddenPolicy.setDosHiddenIfSupported(source, hidden)) {
            return pathResolver.toRelativePath(root, source);
        }

        String name = source.getFileName().toString();
        String targetName = hidden
                ? StorageHiddenPolicy.hiddenName(name)
                : StorageHiddenPolicy.visibleName(name);
        return renameVaultPath(vaultPath, targetName, conflictPolicy);
    }

    public String moveVaultPath(String vaultPath, String targetDirectoryPath) throws IOException {
        return moveVaultPath(vaultPath, targetDirectoryPath, null);
    }

    public String moveVaultPath(String vaultPath, String targetDirectoryPath, ConflictPolicy conflictPolicy)
            throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        Path targetDirectory = pathResolver.resolveDirectory(StorageScope.VAULT, targetDirectoryPath);
        Path target = targetDirectory.resolve(source.getFileName()).normalize();
        pathResolver.ensureInsideBase(StorageScope.VAULT, target);
        if (source.equals(target)) {
            return pathResolver.toRelativePath(root, source);
        }
        if (targetDirectory.startsWith(source)) {
            throw new StorageAccessException("A directory cannot be moved into itself.");
        }
        StorageConflictResolver.StorageConflictTarget resolvedTarget = conflictResolver.resolve(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        treeOperations.move(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public String copyVaultPath(String vaultPath, String targetDirectoryPath) throws IOException {
        return copyVaultPath(vaultPath, targetDirectoryPath, StorageProgressListener.NOOP);
    }

    public String copyVaultPath(String vaultPath, String targetDirectoryPath, ConflictPolicy conflictPolicy)
            throws IOException {
        return copyVaultPath(vaultPath, targetDirectoryPath, StorageProgressListener.NOOP, conflictPolicy);
    }

    public String copyVaultPath(
            String vaultPath,
            String targetDirectoryPath,
            StorageProgressListener progressListener
    ) throws IOException {
        return copyVaultPath(vaultPath, targetDirectoryPath, progressListener, null);
    }

    public String copyVaultPath(
            String vaultPath,
            String targetDirectoryPath,
            StorageProgressListener progressListener,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        StorageProgressListener progress = progressListener == null ? StorageProgressListener.NOOP : progressListener;
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        Path targetDirectory = pathResolver.resolveDirectory(StorageScope.VAULT, targetDirectoryPath);
        Path target = targetDirectory.resolve(source.getFileName()).normalize();
        pathResolver.ensureInsideBase(StorageScope.VAULT, target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && targetDirectory.startsWith(source)) {
            throw new StorageAccessException("A directory cannot be copied into itself.");
        }

        treeOperations.rejectSymbolicLink(source);
        StorageConflictResolver.StorageConflictTarget resolvedTarget = conflictResolver.resolve(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        try {
            progress.checkCanceled();
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                treeOperations.copyDirectory(source, resolvedTarget.path(), progress);
            } else {
                treeOperations.copyFile(source, resolvedTarget.path(), progress, resolvedTarget.overwrite());
            }
        } catch (IOException | RuntimeException ex) {
            if (!resolvedTarget.overwrite() && Files.exists(resolvedTarget.path(), LinkOption.NOFOLLOW_LINKS)) {
                treeOperations.deleteRecursively(resolvedTarget.path());
            }
            throw ex;
        }
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public void deleteVaultPath(String vaultPath) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path path = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        treeOperations.deleteRecursively(path);
    }

    public void moveVaultPathToTrash(String vaultPath, String trashName) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        Path target = pathResolver.resolveTrashChild(trashName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(trashName);
        }
        treeOperations.move(source, target);
    }

    public String restoreTrashItem(String trashName, String originalPath) throws IOException {
        return restoreTrashItem(trashName, originalPath, null);
    }

    public String restoreTrashItem(String trashName, String originalPath, ConflictPolicy conflictPolicy)
            throws IOException {
        Path trashItem = pathResolver.resolveTrashChild(trashName);
        if (!Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(trashName);
        }
        StorageConflictResolver.StorageConflictTarget target = conflictResolver.resolveRestoreTarget(
                originalPath,
                Files.isDirectory(trashItem, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        treeOperations.move(trashItem, target.path(), target.overwrite());
        return pathResolver.toRelativePath(root, target.path());
    }

    public boolean trashItemExists(String trashName) {
        Path trashItem = pathResolver.resolveTrashChild(trashName);
        return Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS);
    }

    public void deleteTrashItemIfExists(String trashName) throws IOException {
        Path trashItem = pathResolver.resolveTrashChild(trashName);
        if (Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS)) {
            treeOperations.deleteRecursively(trashItem);
        }
    }

    public void deleteAllTrashItems() throws IOException {
        Files.createDirectories(trashRoot);
        try (Stream<Path> children = Files.list(trashRoot)) {
            for (Path child : children.collect(Collectors.toList())) {
                treeOperations.deleteRecursively(child);
            }
        }
    }

    public DirectoryListing listSharedDirectory(String sharedBasePath, String requestedPath) throws IOException {
        return listingService.listSharedDirectory(
                sharedBasePath,
                requestedPath,
                shareProperties.isDirectoryShowHiddenItems()
        );
    }

    public Path resolveSharedFile(String sharedBasePath, String requestedPath, String fileName) throws IOException {
        pathResolver.validateSingleName(fileName);
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = pathResolver.resolveSharedPath(sharedBase, requestedPath);
        Path file = directory.resolve(fileName).normalize();
        pathResolver.ensureInsideSharedBase(sharedBase, file);
        pathResolver.rejectVaultSystemPath(file);
        pathResolver.ensureExistingPathInsideSharedBase(sharedBase, file);
        rejectHiddenSharedPathIfNeeded(sharedBase, file);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public FileItem describeSharedFile(String sharedBasePath, String requestedPath, String fileName) throws IOException {
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path file = resolveSharedFile(sharedBasePath, requestedPath, fileName);
        return listingService.toFileItem(sharedBase, file);
    }

    public String mediaType(Path file) throws IOException {
        return listingService.mediaType(file);
    }

    public Path createFileStagingTemporaryFile(String prefix, String suffix) throws IOException {
        return fileStagingService.createTemporaryFile(prefix, suffix);
    }

    public Path resumableUploadProtocolRoot() throws IOException {
        return fileStagingService.resumableProtocolRoot();
    }

    public Path claimResumableUploadData(Path protocolData, String sessionId) throws IOException {
        return fileStagingService.claimResumableUpload(protocolData, sessionId);
    }

    public Path resumableUploadStagingFile(String sessionId) {
        return fileStagingService.resumableStagingFile(sessionId);
    }

    public String fileStagingFilename(Path path) {
        return fileStagingService.filename(path);
    }

    public Path resolveFileStagingFile(String filename) {
        return fileStagingService.resolveFile(filename);
    }

    public List<FileStagingInfo> listFileStagingFiles() throws IOException {
        return fileStagingService.listFiles();
    }

    public void deleteFileStagingFile(String filename) throws IOException {
        fileStagingService.deleteFile(filename);
    }

    public FileItem moveTemporaryFileIntoVault(Path temporaryFile, String directoryPath, String filename)
            throws IOException {
        return moveTemporaryFileIntoVault(temporaryFile, directoryPath, filename, null);
    }

    public FileItem moveTemporaryFileIntoVault(
            Path temporaryFile,
            String directoryPath,
            String filename,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        CommittedVaultFile committedFile = commitTemporaryFileIntoVault(
                temporaryFile,
                directoryPath,
                filename,
                conflictPolicy
        );
        return listingService.describeVaultPath(committedFile.path());
    }

    public CommittedVaultFile commitTemporaryFileIntoVault(
            Path temporaryFile,
            String directoryPath,
            String filename,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        StorageConflictResolver.StorageConflictTarget resolvedTarget =
                conflictResolver.resolve(target, false, conflictPolicy);
        treeOperations.move(temporaryFile, resolvedTarget.path(), resolvedTarget.overwrite());
        return new CommittedVaultFile(
                resolvedTarget.path().getFileName().toString(),
                pathResolver.toRelativePath(root, resolvedTarget.path())
        );
    }

    public String resolveAvailableVaultFilename(String directoryPath, String filename) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        StorageConflictResolver.StorageConflictTarget resolvedTarget =
                conflictResolver.resolve(target, false, ConflictPolicy.RENAME);
        return resolvedTarget.path().getFileName().toString();
    }

    public CommittedVaultFile commitStagedRegularFileNoReplace(
            Path stagedFile,
            String directoryPath,
            String filename
    ) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        treeOperations.commitRegularFileNoReplace(stagedFile, target);
        return new CommittedVaultFile(
                target.getFileName().toString(),
                pathResolver.toRelativePath(root, target)
        );
    }

    public void createStagedRegularFileReplacementBackup(
            String directoryPath,
            String filename,
            Path backupFile
    ) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, true);
        Path canonicalBackup = resolveFileStagingFile(fileStagingFilename(backupFile));
        treeOperations.createRegularFileReplacementBackup(target, canonicalBackup);
    }

    public CommittedVaultFile commitStagedRegularFileReplace(
            Path stagedFile,
            String directoryPath,
            String filename
    ) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, true);
        treeOperations.commitRegularFileReplace(stagedFile, target);
        return new CommittedVaultFile(
                target.getFileName().toString(),
                pathResolver.toRelativePath(root, target)
        );
    }

    public void deleteStagedRegularFileReplacementBackup(Path backupFile) throws IOException {
        Path canonicalBackup = resolveFileStagingFile(fileStagingFilename(backupFile));
        treeOperations.deleteRegularFileReplacementBackup(canonicalBackup);
    }

    public Path createArchiveExtractionWorkspace() throws IOException {
        return archiveStagingCommitter.createWorkspace("extract-");
    }

    public Path createArchiveCreationWorkspace() throws IOException {
        return archiveStagingCommitter.createWorkspace("create-");
    }

    public Path claimArchiveCreationOutput(Path archiveOutput) throws IOException {
        Path safeOutput = archiveStagingCommitter.requireCreationOutput(archiveOutput);
        return fileStagingService.claimTemporaryFile(safeOutput, "archive-output-", ".tmp");
    }

    public FileItem commitTemporaryDirectoryIntoVault(
            Path temporaryDirectory,
            String directoryPath,
            String directoryName,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        return archiveStagingCommitter.commitDirectory(
                temporaryDirectory,
                directoryPath,
                directoryName,
                conflictPolicy
        );
    }

    public void preflightArchiveExtraction(
            String directoryPath,
            boolean createContainingDirectory,
            String directoryName,
            List<StorageBatchEntry> topLevelEntries,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        archiveStagingCommitter.preflight(
                directoryPath,
                createContainingDirectory,
                directoryName,
                topLevelEntries,
                conflictPolicy
        );
    }

    public StorageBatchCommitResult commitArchiveContentsIntoVault(
            Path temporaryDirectory,
            String directoryPath,
            List<StorageBatchEntry> topLevelEntries,
            ConflictPolicy conflictPolicy,
            StorageProgressListener progressListener
    ) throws IOException {
        return archiveStagingCommitter.commitContents(
                temporaryDirectory,
                directoryPath,
                topLevelEntries,
                conflictPolicy,
                progressListener
        );
    }

    public void deleteArchiveExtractionWorkspace(Path workspace) throws IOException {
        archiveStagingCommitter.deleteWorkspace(workspace);
    }

    public void deleteArchiveCreationWorkspace(Path workspace) throws IOException {
        archiveStagingCommitter.deleteWorkspace(workspace);
    }

    public List<ArchiveStagingInfo> listArchiveStagingArtifacts(StorageProgressListener progressListener)
            throws IOException {
        return archiveStagingCommitter.listArtifacts(progressListener);
    }

    public void deleteArchiveStagingArtifact(String name) throws IOException {
        archiveStagingCommitter.deleteArtifact(name);
    }

    public void preflightVaultFileCommit(
            String directoryPath,
            String filename,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        conflictResolver.resolve(target, false, conflictPolicy);
    }

    public void validateVaultEntryName(String name) {
        pathResolver.validateSingleName(name);
    }

    public StorageOperationSummary summarizeVaultPaths(List<String> vaultPaths) throws IOException {
        long totalBytes = 0L;
        long totalItems = 0L;
        for (String vaultPath : vaultPaths == null ? List.<String>of() : vaultPaths) {
            pathResolver.validateVaultItemPath(vaultPath);
            StorageOperationSummary summary = treeOperations.summarize(pathResolver.resolve(StorageScope.VAULT, vaultPath));
            totalBytes += summary.totalBytes();
            totalItems += summary.totalItems();
        }
        return new StorageOperationSummary(totalBytes, totalItems);
    }

    public ConflictPolicy defaultConflictPolicy() {
        return conflictResolver.defaultPolicy();
    }

    public void createDirectory(String directoryPath, String name) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, name, false);
        Files.createDirectory(target);
    }

    public void createFile(String directoryPath, String name) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, name, false);
        Files.createFile(target);
    }

    public String rename(String directoryPath, String itemName, String newName) throws IOException {
        return rename(directoryPath, itemName, newName, null);
    }

    public String rename(String directoryPath, String itemName, String newName, ConflictPolicy conflictPolicy)
            throws IOException {
        Path source = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, itemName, true);
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, newName, false);
        if (source.equals(target)) {
            return pathResolver.toRelativePath(root, source);
        }
        StorageConflictResolver.StorageConflictTarget resolvedTarget = conflictResolver.resolve(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        treeOperations.move(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public String move(String sourceDirectoryPath, String itemName, String targetDirectoryPath) throws IOException {
        return move(sourceDirectoryPath, itemName, targetDirectoryPath, null);
    }

    public String move(
            String sourceDirectoryPath,
            String itemName,
            String targetDirectoryPath,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Path source = pathResolver.resolveChild(StorageScope.VAULT, sourceDirectoryPath, itemName, true);
        Path targetDirectory = pathResolver.resolveDirectory(StorageScope.VAULT, targetDirectoryPath);
        Path target = pathResolver.resolveChild(StorageScope.VAULT, targetDirectoryPath, source.getFileName().toString(), false);
        if (source.equals(target)) {
            return pathResolver.toRelativePath(root, source);
        }
        if (targetDirectory.startsWith(source)) {
            throw new StorageAccessException("A directory cannot be moved into itself.");
        }
        StorageConflictResolver.StorageConflictTarget resolvedTarget = conflictResolver.resolve(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        treeOperations.move(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public void delete(String directoryPath, List<String> itemNames) throws IOException {
        for (String itemName : itemNames) {
            Path item = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, itemName, true);
            treeOperations.deleteRecursively(item);
        }
    }

    public void writeZip(StorageScope scope, String directoryPath, List<String> itemNames, OutputStream outputStream)
            throws IOException {
        writeZip(scope, directoryPath, itemNames, outputStream, StorageProgressListener.NOOP);
    }

    public void writeZip(
            StorageScope scope,
            String directoryPath,
            List<String> itemNames,
            OutputStream outputStream,
            StorageProgressListener progressListener
    ) throws IOException {
        try (StorageZipWriter.EntryWriter zip = zipWriter.open(outputStream, progressListener)) {
            for (String itemName : itemNames) {
                Path item = pathResolver.resolveChild(scope, directoryPath, itemName, true);
                zip.write(item, item.getFileName().toString());
            }
        }
    }

    public void writeVaultPathsZip(List<String> vaultPaths, OutputStream outputStream) throws IOException {
        try (StorageZipWriter.EntryWriter zip = zipWriter.open(outputStream)) {
            for (String vaultPath : vaultPaths) {
                pathResolver.validateVaultItemPath(vaultPath);
                Path item = pathResolver.resolve(StorageScope.VAULT, vaultPath);
                zip.write(item, pathResolver.toRelativePath(root, item));
            }
        }
    }

    public void writeSharedZip(String sharedBasePath, String directoryPath, List<String> itemNames,
            OutputStream outputStream) throws IOException {
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = pathResolver.resolveSharedPath(sharedBase, directoryPath);
        rejectHiddenSharedPathIfNeeded(sharedBase, directory);

        try (StorageZipWriter.EntryWriter zip = zipWriter.open(outputStream)) {
            for (String itemName : itemNames) {
                pathResolver.validateSingleName(itemName);
                Path item = directory.resolve(itemName).normalize();
                pathResolver.ensureInsideSharedBase(sharedBase, item);
                pathResolver.rejectVaultSystemPath(item);
                pathResolver.ensureExistingPathInsideSharedBase(sharedBase, item);
                rejectHiddenSharedPathIfNeeded(sharedBase, item);
                zip.write(item, item.getFileName().toString());
            }
        }
    }

    private void rejectHiddenSharedPathIfNeeded(Path sharedBase, Path target) throws IOException {
        if (!shareProperties.isDirectoryShowHiddenItems()
                && StorageHiddenPolicy.containsHiddenElement(sharedBase, target)) {
            throw new NoSuchFileException(pathResolver.toRelativePath(sharedBase, target));
        }
    }

    private String validateConfiguredDirectory(String directoryName) {
        if (directoryName == null || directoryName.isBlank()) {
            throw new StorageAccessException("Storage system directory name is required.");
        }
        try {
            StoragePathResolver.validateSingleNameValue(directoryName);
        } catch (StorageAccessException ex) {
            throw new StorageAccessException("Invalid storage system directory: " + directoryName);
        }
        return directoryName;
    }

    private void createSystemDirectory(Path directory, String label) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new StorageAccessException(label + " directory cannot be a symbolic link.");
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException(label + " path is not a directory.");
        }
    }

    public record CommittedVaultFile(String name, String path) {
    }

    public record FileStagingInfo(
            String name,
            long size,
            String sizeLabel,
            Instant modifiedAt,
            String modifiedLabel,
            boolean active,
            String activeOperation
    ) {
    }

    public record ArchiveStagingInfo(
            String name,
            String operation,
            long size,
            String sizeLabel,
            Instant modifiedAt,
            String modifiedLabel,
            boolean active,
            String activeOperation
    ) {
    }
}
