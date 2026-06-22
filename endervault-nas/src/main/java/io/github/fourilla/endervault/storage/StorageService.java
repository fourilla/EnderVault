package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final NasProperties nasProperties;
    private final Path root;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final Path uploadTempRoot;
    private final String trashDirectoryName;
    private final String metadataDirectoryName;
    private final FileActionRegistry fileActionRegistry;
    private final StoragePathResolver pathResolver;
    private final StorageZipWriter zipWriter;
    private final UploadStagingService uploadStagingService;

    public StorageService(NasProperties nasProperties) {
        this(nasProperties, new FileActionRegistry());
    }

    @Autowired
    public StorageService(NasProperties nasProperties, FileActionRegistry fileActionRegistry) {
        this.nasProperties = nasProperties;
        NasProperties.Storage storage = nasProperties.getStorage();
        this.root = storage.getRoot().toAbsolutePath().normalize();
        this.trashDirectoryName = validateConfiguredDirectory(storage.getTrashDirectory());
        this.metadataDirectoryName = validateConfiguredDirectory(storage.getMetadataDirectory());
        this.trashRoot = root.resolve(trashDirectoryName).normalize();
        this.metadataRoot = root.resolve(metadataDirectoryName).normalize();
        this.uploadTempRoot = metadataRoot.resolve("uploads").normalize();
        this.fileActionRegistry = fileActionRegistry;
        this.pathResolver = new StoragePathResolver(root, trashRoot, metadataRoot, uploadTempRoot);
        this.zipWriter = new StorageZipWriter();
        this.uploadStagingService = new UploadStagingService(uploadTempRoot, pathResolver);
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(trashRoot);
        Files.createDirectories(metadataRoot);
        Files.createDirectories(uploadTempRoot);
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
        Path directory = pathResolver.resolveDirectory(scope, requestedPath);
        String currentPath = pathResolver.toRelativePath(pathResolver.baseFor(scope), directory);
        List<FileItem> children;

        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isHiddenSystemPath(scope, path))
                    .map(path -> toFileItem(pathResolver.baseFor(scope), path))
                    .sorted(itemComparator(sort, direction))
                    .toList();
        }

        List<FileItem> directories = children.stream().filter(FileItem::directory).toList();
        List<FileItem> files = children.stream().filter(item -> !item.directory()).toList();

        return new DirectoryListing(
                currentPath,
                parentPathOf(currentPath).orElse(null),
                breadcrumbsFor(currentPath),
                directories,
                files
        );
    }

    public DirectoryListing listTrash() throws IOException {
        Files.createDirectories(trashRoot);
        List<FileItem> children;
        try (Stream<Path> stream = Files.list(trashRoot)) {
            children = stream
                    .map(path -> toFileItem(trashRoot, path))
                    .sorted(itemComparator(FileSort.NAME, SortDirection.ASC))
                    .toList();
        }

        return new DirectoryListing(
                "",
                null,
                List.of(new Breadcrumb("Trash", "")),
                children.stream().filter(FileItem::directory).toList(),
                children.stream().filter(item -> !item.directory()).toList()
        );
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
        String normalizedQuery = normalizeSearchQuery(query);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        Path searchRoot = pathResolver.resolveDirectory(scope, requestedRoot);
        if (pathResolver.isHiddenSystemPath(scope, searchRoot)) {
            throw new NoSuchFileException(requestedRoot == null ? "" : requestedRoot);
        }

        List<FileItem> results = new ArrayList<>();
        searchRecursively(scope, searchRoot, normalizedQuery, results);
        return results.stream()
                .sorted(Comparator.comparing(item -> item.path().toLowerCase(Locale.ROOT)))
                .toList();
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

    public FileItem describeVaultPath(String vaultPath) throws IOException {
        return toFileItem(root, pathResolver.resolve(StorageScope.VAULT, vaultPath));
    }

    public FileDetail detail(StorageScope scope, String vaultPath) throws IOException {
        Path path = pathResolver.resolve(scope, vaultPath);
        return toFileDetail(pathResolver.baseFor(scope), path);
    }

    public FileItem describeVaultChild(String directoryPath, String itemName) throws IOException {
        return toFileItem(root, pathResolver.resolveChild(StorageScope.VAULT, directoryPath, itemName, true));
    }

    public Path ensureVaultDirectory(String vaultPath) throws IOException {
        return pathResolver.resolveDirectory(StorageScope.VAULT, vaultPath);
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
        ConflictTarget resolvedTarget = resolveConflictTarget(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        movePath(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
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
        ConflictTarget resolvedTarget = resolveConflictTarget(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        movePath(source, resolvedTarget.path(), resolvedTarget.overwrite());
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

        rejectSymbolicLink(source);
        ConflictTarget resolvedTarget = resolveConflictTarget(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        try {
            progress.checkCanceled();
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(source, resolvedTarget.path(), progress);
            } else {
                copyFile(source, resolvedTarget.path(), progress, resolvedTarget.overwrite());
            }
        } catch (IOException | RuntimeException ex) {
            if (!resolvedTarget.overwrite() && Files.exists(resolvedTarget.path(), LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(resolvedTarget.path());
            }
            throw ex;
        }
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public void deleteVaultPath(String vaultPath) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path path = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        deleteRecursively(path);
    }

    public void moveVaultPathToTrash(String vaultPath, String trashName) throws IOException {
        pathResolver.validateVaultItemPath(vaultPath);
        Path source = pathResolver.resolve(StorageScope.VAULT, vaultPath);
        Path target = pathResolver.resolveTrashChild(trashName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(trashName);
        }
        movePath(source, target);
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
        ConflictTarget target = resolveRestoreTarget(
                originalPath,
                Files.isDirectory(trashItem, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        movePath(trashItem, target.path(), target.overwrite());
        return pathResolver.toRelativePath(root, target.path());
    }

    public boolean trashItemExists(String trashName) {
        Path trashItem = pathResolver.resolveTrashChild(trashName);
        return Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS);
    }

    public void deleteTrashItemIfExists(String trashName) throws IOException {
        Path trashItem = pathResolver.resolveTrashChild(trashName);
        if (Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(trashItem);
        }
    }

    public void deleteAllTrashItems() throws IOException {
        Files.createDirectories(trashRoot);
        try (Stream<Path> children = Files.list(trashRoot)) {
            for (Path child : children.collect(Collectors.toList())) {
                deleteRecursively(child);
            }
        }
    }

    public DirectoryListing listSharedDirectory(String sharedBasePath, String requestedPath) throws IOException {
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = pathResolver.resolveSharedPath(sharedBase, requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }

        String currentPath = pathResolver.toRelativePath(sharedBase, directory);
        List<FileItem> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isVaultSystemPath(path))
                    .map(path -> toFileItem(sharedBase, path))
                    .sorted(itemComparator(FileSort.NAME, SortDirection.ASC))
                    .toList();
        }

        return new DirectoryListing(
                currentPath,
                parentPathOf(currentPath).orElse(null),
                breadcrumbsFor(currentPath),
                children.stream().filter(FileItem::directory).toList(),
                children.stream().filter(item -> !item.directory()).toList()
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
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public FileItem describeSharedFile(String sharedBasePath, String requestedPath, String fileName) throws IOException {
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path file = resolveSharedFile(sharedBasePath, requestedPath, fileName);
        return toFileItem(sharedBase, file);
    }

    public String mediaType(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        return mediaType == null ? "application/octet-stream" : mediaType;
    }

    public FileItem upload(String directoryPath, MultipartFile file) throws IOException {
        return upload(directoryPath, file, null);
    }

    public FileItem upload(String directoryPath, MultipartFile file, ConflictPolicy conflictPolicy) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        StagedUpload stagedUpload = stageUpload(file);
        try {
            FileItem item = moveStagedUploadIntoVault(stagedUpload, directoryPath, conflictPolicy);
            stagedUpload = null;
            return item;
        } finally {
            if (stagedUpload != null) {
                Files.deleteIfExists(stagedUpload.temporaryFile());
            }
        }
    }

    public StagedUpload stageUpload(MultipartFile file) throws IOException {
        return uploadStagingService.stageUpload(file);
    }

    public Path createUploadTemporaryFile(String prefix, String suffix) throws IOException {
        return uploadStagingService.createTemporaryFile(prefix, suffix);
    }

    public List<TemporaryFileInfo> listUploadTemporaryFiles() throws IOException {
        return uploadStagingService.listTemporaryFiles();
    }

    public void deleteUploadTemporaryFile(String filename) throws IOException {
        uploadStagingService.deleteTemporaryFile(filename);
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
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        ConflictTarget resolvedTarget = resolveConflictTarget(target, false, conflictPolicy);
        movePath(temporaryFile, resolvedTarget.path(), resolvedTarget.overwrite());
        return toFileItem(root, resolvedTarget.path());
    }

    public FileItem moveStagedUploadIntoVault(
            StagedUpload stagedUpload,
            String directoryPath,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        if (stagedUpload == null) {
            return null;
        }
        return moveTemporaryFileIntoVault(
                stagedUpload.temporaryFile(),
                directoryPath,
                stagedUpload.filename(),
                conflictPolicy
        );
    }

    public StorageOperationSummary summarizeVaultPaths(List<String> vaultPaths) throws IOException {
        long totalBytes = 0L;
        long totalItems = 0L;
        for (String vaultPath : vaultPaths == null ? List.<String>of() : vaultPaths) {
            pathResolver.validateVaultItemPath(vaultPath);
            StorageOperationSummary summary = summarizePath(pathResolver.resolve(StorageScope.VAULT, vaultPath));
            totalBytes += summary.totalBytes();
            totalItems += summary.totalItems();
        }
        return new StorageOperationSummary(totalBytes, totalItems);
    }

    private void movePath(Path source, Path target) throws IOException {
        movePath(source, target, false);
    }

    private void movePath(Path source, Path target, boolean overwrite) throws IOException {
        StandardCopyOption[] options = overwrite
                ? new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING }
                : new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE };
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException ex) {
            if (overwrite) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        copyDirectory(source, target, StorageProgressListener.NOOP);
    }

    private void copyDirectory(Path source, Path target, StorageProgressListener progress) throws IOException {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    progress.checkCanceled();
                    rejectSymbolicLink(directory);
                    Path relative = source.relativize(directory);
                    Path targetDirectory = target.resolve(relative).normalize();
                    pathResolver.ensureInsideBase(StorageScope.VAULT, targetDirectory);
                    Files.createDirectory(targetDirectory);
                    progress.onItemProcessed();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    progress.checkCanceled();
                    rejectSymbolicLink(file);
                    Path relative = source.relativize(file);
                    Path targetFile = target.resolve(relative).normalize();
                    pathResolver.ensureInsideBase(StorageScope.VAULT, targetFile);
                    copyFile(file, targetFile, progress);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ex) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(target);
            }
            throw ex;
        }
    }

    private void copyFile(Path source, Path target, StorageProgressListener progress) throws IOException {
        copyFile(source, target, progress, false);
    }

    private void copyFile(Path source, Path target, StorageProgressListener progress, boolean overwrite)
            throws IOException {
        if (!overwrite) {
            copyFileDirect(source, target, progress);
            return;
        }

        Path temporaryFile = createUploadTemporaryFile("copy-overwrite-", ".tmp");
        try {
            Files.deleteIfExists(temporaryFile);
            copyFileDirect(source, temporaryFile, progress);
            movePath(temporaryFile, target, true);
            temporaryFile = null;
        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private void copyFileDirect(Path source, Path target, StorageProgressListener progress) throws IOException {
        progress.checkCanceled();
        try (InputStream inputStream = Files.newInputStream(source);
                OutputStream outputStream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                progress.checkCanceled();
                outputStream.write(buffer, 0, read);
                progress.onBytesProcessed(read);
            }
        }
        progress.onItemProcessed();
    }

    public ConflictPolicy defaultConflictPolicy() {
        return ConflictPolicy.from(nasProperties.getStorage().getDefaultConflictPolicy());
    }

    private ConflictTarget resolveConflictTarget(Path requestedTarget, boolean sourceDirectory, ConflictPolicy policy)
            throws IOException {
        if (!Files.exists(requestedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return new ConflictTarget(requestedTarget, false);
        }

        ConflictPolicy effectivePolicy = effectiveConflictPolicy(policy);
        return switch (effectivePolicy) {
            case CANCEL -> throw new FileAlreadyExistsException(requestedTarget.getFileName().toString());
            case RENAME -> new ConflictTarget(nextAvailableTarget(requestedTarget, sourceDirectory), false);
            case OVERWRITE -> {
                validateOverwriteTarget(requestedTarget, sourceDirectory);
                yield new ConflictTarget(requestedTarget, true);
            }
        };
    }

    private ConflictPolicy effectiveConflictPolicy(ConflictPolicy policy) {
        ConflictPolicy effective = policy == null ? defaultConflictPolicy() : policy;
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

    private void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new StorageAccessException("Symbolic links cannot be copied.");
        }
    }

    public void createDirectory(String directoryPath, String name) throws IOException {
        Path target = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, name, false);
        Files.createDirectory(target);
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
        ConflictTarget resolvedTarget = resolveConflictTarget(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        movePath(source, resolvedTarget.path(), resolvedTarget.overwrite());
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
        ConflictTarget resolvedTarget = resolveConflictTarget(
                target,
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS),
                conflictPolicy
        );
        movePath(source, resolvedTarget.path(), resolvedTarget.overwrite());
        return pathResolver.toRelativePath(root, resolvedTarget.path());
    }

    public void delete(String directoryPath, List<String> itemNames) throws IOException {
        for (String itemName : itemNames) {
            Path item = pathResolver.resolveChild(StorageScope.VAULT, directoryPath, itemName, true);
            deleteRecursively(item);
        }
    }

    public void writeZip(StorageScope scope, String directoryPath, List<String> itemNames, OutputStream outputStream)
            throws IOException {
        try (StorageZipWriter.EntryWriter zip = zipWriter.open(outputStream)) {
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

        try (StorageZipWriter.EntryWriter zip = zipWriter.open(outputStream)) {
            for (String itemName : itemNames) {
                pathResolver.validateSingleName(itemName);
                Path item = directory.resolve(itemName).normalize();
                pathResolver.ensureInsideSharedBase(sharedBase, item);
                pathResolver.rejectVaultSystemPath(item);
                pathResolver.ensureExistingPathInsideSharedBase(sharedBase, item);
                zip.write(item, item.getFileName().toString());
            }
        }
    }

    private ConflictTarget resolveRestoreTarget(String vaultPath, boolean sourceDirectory, ConflictPolicy conflictPolicy)
            throws IOException {
        Path target = pathResolver.resolveRestoreTargetPath(vaultPath);
        return resolveConflictTarget(target, sourceDirectory, conflictPolicy);
    }

    private void searchRecursively(
            StorageScope scope,
            Path directory,
            String normalizedQuery,
            List<FileItem> results
    ) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path child : stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isHiddenSystemPath(scope, path))
                    .sorted(pathNameComparator())
                    .collect(Collectors.toList())) {
                if (matchesSearchQuery(child, normalizedQuery)) {
                    results.add(toFileItem(pathResolver.baseFor(scope), child));
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    searchRecursively(scope, child, normalizedQuery, results);
                }
            }
        }
    }

    private boolean matchesSearchQuery(Path path, String normalizedQuery) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private String normalizeSearchQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<String> parentPathOf(String currentPath) {
        if (currentPath == null || currentPath.isBlank()) {
            return Optional.empty();
        }
        int index = currentPath.lastIndexOf('/');
        return Optional.of(index < 0 ? "" : currentPath.substring(0, index));
    }

    private List<Breadcrumb> breadcrumbsFor(String currentPath) {
        List<Breadcrumb> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(new Breadcrumb("Root", ""));
        if (currentPath == null || currentPath.isBlank()) {
            return breadcrumbs;
        }

        String[] segments = currentPath.split("/");
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
            breadcrumbs.add(new Breadcrumb(segment, path.toString()));
        }
        return breadcrumbs;
    }

    private FileItem toFileItem(Path relativeBase, Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            String mediaType = directory ? "directory" : mediaType(path);
            long size = directory ? 0L : Files.size(path);
            String relativePath = pathResolver.toRelativePath(relativeBase, path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String extension = extensionOf(path, directory);
            return new FileItem(
                    path.getFileName().toString(),
                    relativePath,
                    directory,
                    size,
                    directory ? "-" : ByteSizeFormatter.humanSize(size),
                    MODIFIED_FORMATTER.format(modified),
                    modified,
                    mediaType,
                    fileActionRegistry.previewPageAvailable(path.getFileName().toString(), directory, mediaType, extension),
                    mediaType.startsWith("video/")
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read file metadata.", ex);
        }
    }

    private FileDetail toFileDetail(Path relativeBase, Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        String mediaType = directory ? "directory" : mediaType(path);
        long size = directory ? 0L : Files.size(path);
        String relativePath = pathResolver.toRelativePath(relativeBase, path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        String extension = extensionOf(path, directory);
        return new FileDetail(
                path.getFileName().toString(),
                relativePath,
                parentPathOf(relativePath).orElse(null),
                directory,
                size,
                directory ? "-" : ByteSizeFormatter.humanSize(size),
                directory ? childCount(path) : 0L,
                MODIFIED_FORMATTER.format(attributes.creationTime().toInstant()),
                MODIFIED_FORMATTER.format(attributes.lastModifiedTime().toInstant()),
                MODIFIED_FORMATTER.format(attributes.lastAccessTime().toInstant()),
                mediaType,
                extension,
                fileActionRegistry.previewPageAvailable(path.getFileName().toString(), directory, mediaType, extension),
                mediaType.startsWith("video/")
        );
    }

    private long childCount(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(path -> !pathResolver.isHiddenSystemPath(StorageScope.VAULT, path))
                    .count();
        }
    }

    private String extensionOf(Path path, boolean directory) {
        if (directory) {
            return "";
        }
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');
        if (index <= 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private Comparator<FileItem> itemComparator(FileSort sort, SortDirection direction) {
        Comparator<FileItem> nameComparator = Comparator.comparing(
                item -> item.name().toLowerCase(Locale.ROOT)
        );
        Comparator<FileItem> primary = switch (sort) {
            case SIZE -> Comparator.comparingLong(FileItem::size);
            case MODIFIED -> Comparator.comparing(FileItem::modifiedAt);
            case TYPE -> Comparator.comparing(
                    (FileItem item) -> item.typeLabel().toLowerCase(Locale.ROOT)
            ).thenComparing(item -> item.mediaType().toLowerCase(Locale.ROOT));
            case NAME -> nameComparator;
        };
        if (direction == SortDirection.DESC) {
            primary = primary.reversed();
        }
        return primary.thenComparing(nameComparator);
    }

    private Comparator<Path> pathNameComparator() {
        return Comparator
                .comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private StorageOperationSummary summarizePath(Path path) throws IOException {
        rejectSymbolicLink(path);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new StorageOperationSummary(Files.size(path), 1L);
        }

        final long[] totalBytes = {0L};
        final long[] totalItems = {0L};
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectSymbolicLink(directory);
                totalItems[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectSymbolicLink(file);
                totalItems[0]++;
                totalBytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new StorageOperationSummary(totalBytes[0], totalItems[0]);
    }

    private String validateConfiguredDirectory(String directoryName) {
        if (directoryName == null || directoryName.isBlank()) {
            throw new StorageAccessException("Storage system directory name is required.");
        }
        if (directoryName.contains("/") || directoryName.contains("\\") || ".".equals(directoryName)
                || "..".equals(directoryName) || directoryName.contains(":")) {
            throw new StorageAccessException("Invalid storage system directory: " + directoryName);
        }
        return directoryName;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.collect(Collectors.toList())) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    public record StagedUpload(Path temporaryFile, String filename, long size) {
    }

    public record TemporaryFileInfo(
            String name,
            long size,
            String sizeLabel,
            Instant modifiedAt,
            String modifiedLabel
    ) {
    }

    private record ConflictTarget(Path path, boolean overwrite) {
    }
}
