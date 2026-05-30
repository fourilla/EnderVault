package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path root;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final Path uploadTempRoot;
    private final String trashDirectoryName;
    private final String metadataDirectoryName;

    public StorageService(NasProperties nasProperties) {
        NasProperties.Storage storage = nasProperties.getStorage();
        this.root = storage.getRoot().toAbsolutePath().normalize();
        this.trashDirectoryName = validateConfiguredDirectory(storage.getTrashDirectory());
        this.metadataDirectoryName = validateConfiguredDirectory(storage.getMetadataDirectory());
        this.trashRoot = root.resolve(trashDirectoryName).normalize();
        this.metadataRoot = root.resolve(metadataDirectoryName).normalize();
        this.uploadTempRoot = metadataRoot.resolve("uploads").normalize();
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
        Path directory = resolveDirectory(scope, requestedPath);
        String currentPath = toRelativePath(baseFor(scope), directory);
        List<FileItem> children;

        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isHiddenSystemPath(scope, path))
                    .map(path -> toFileItem(baseFor(scope), path))
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

        Path searchRoot = resolveDirectory(scope, requestedRoot);
        if (isHiddenSystemPath(scope, searchRoot)) {
            throw new NoSuchFileException(requestedRoot == null ? "" : requestedRoot);
        }

        List<FileItem> results = new ArrayList<>();
        searchRecursively(scope, searchRoot, normalizedQuery, results);
        return results.stream()
                .sorted(Comparator.comparing(item -> item.path().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public Path resolveFile(StorageScope scope, String directoryPath, String fileName) throws IOException {
        Path file = resolveChild(scope, directoryPath, fileName, true);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public Path resolveVaultFile(String vaultPath) throws IOException {
        Path file = resolve(StorageScope.VAULT, vaultPath);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(vaultPath);
        }
        return file;
    }

    public Path resolveVaultPath(String vaultPath) throws IOException {
        return resolve(StorageScope.VAULT, vaultPath);
    }

    public FileItem describeVaultPath(String vaultPath) throws IOException {
        return toFileItem(root, resolve(StorageScope.VAULT, vaultPath));
    }

    public FileDetail detail(StorageScope scope, String vaultPath) throws IOException {
        Path path = resolve(scope, vaultPath);
        return toFileDetail(baseFor(scope), path);
    }

    public FileItem describeVaultChild(String directoryPath, String itemName) throws IOException {
        return toFileItem(root, resolveChild(StorageScope.VAULT, directoryPath, itemName, true));
    }

    public String renameVaultPath(String vaultPath, String newName) throws IOException {
        validateVaultItemPath(vaultPath);
        Path source = resolve(StorageScope.VAULT, vaultPath);
        Path target = source.resolveSibling(newName).normalize();
        validateSingleName(newName);
        ensureInsideBase(StorageScope.VAULT, target);
        ensureParentInsideBase(StorageScope.VAULT, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(newName);
        }
        Files.move(source, target);
        return toRelativePath(root, target);
    }

    public String moveVaultPath(String vaultPath, String targetDirectoryPath) throws IOException {
        validateVaultItemPath(vaultPath);
        Path source = resolve(StorageScope.VAULT, vaultPath);
        Path targetDirectory = resolveDirectory(StorageScope.VAULT, targetDirectoryPath);
        Path target = targetDirectory.resolve(source.getFileName()).normalize();
        ensureInsideBase(StorageScope.VAULT, target);
        if (targetDirectory.startsWith(source)) {
            throw new StorageAccessException("A directory cannot be moved into itself.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.getFileName().toString());
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        return toRelativePath(root, target);
    }

    public void deleteVaultPath(String vaultPath) throws IOException {
        validateVaultItemPath(vaultPath);
        Path path = resolve(StorageScope.VAULT, vaultPath);
        deleteRecursively(path);
    }

    public void moveVaultPathToTrash(String vaultPath, String trashName) throws IOException {
        validateVaultItemPath(vaultPath);
        Path source = resolve(StorageScope.VAULT, vaultPath);
        Path target = resolveTrashChild(trashName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(trashName);
        }
        movePath(source, target);
    }

    public void restoreTrashItem(String trashName, String originalPath) throws IOException {
        Path trashItem = resolveTrashChild(trashName);
        if (!Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(trashName);
        }
        Path target = resolveRestoreTarget(originalPath);
        movePath(trashItem, target);
    }

    public boolean trashItemExists(String trashName) {
        Path trashItem = resolveTrashChild(trashName);
        return Files.exists(trashItem, LinkOption.NOFOLLOW_LINKS);
    }

    public void deleteTrashItemIfExists(String trashName) throws IOException {
        Path trashItem = resolveTrashChild(trashName);
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
        Path sharedBase = resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = resolveSharedPath(sharedBase, requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }

        String currentPath = toRelativePath(sharedBase, directory);
        List<FileItem> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isVaultSystemPath(path))
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
        validateSingleName(fileName);
        Path sharedBase = resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = resolveSharedPath(sharedBase, requestedPath);
        Path file = directory.resolve(fileName).normalize();
        ensureInsideSharedBase(sharedBase, file);
        rejectVaultSystemPath(file);
        ensureExistingPathInsideSharedBase(sharedBase, file);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public String mediaType(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        return mediaType == null ? "application/octet-stream" : mediaType;
    }

    public FileItem upload(String directoryPath, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        String filename = safeSubmittedFilename(file);
        Path target = resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(filename);
        }
        Files.createDirectories(uploadTempRoot);
        Path temporaryFile = Files.createTempFile(uploadTempRoot, "upload-", ".tmp");
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            moveUploadedFileIntoPlace(temporaryFile, target);
            temporaryFile = null;
        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
        return toFileItem(root, target);
    }

    private void moveUploadedFileIntoPlace(Path temporaryFile, Path target) throws IOException {
        movePath(temporaryFile, target);
    }

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    public void createDirectory(String directoryPath, String name) throws IOException {
        Path target = resolveChild(StorageScope.VAULT, directoryPath, name, false);
        Files.createDirectory(target);
    }

    public void rename(String directoryPath, String itemName, String newName) throws IOException {
        Path source = resolveChild(StorageScope.VAULT, directoryPath, itemName, true);
        Path target = resolveChild(StorageScope.VAULT, directoryPath, newName, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(newName);
        }
        Files.move(source, target);
    }

    public void move(String sourceDirectoryPath, String itemName, String targetDirectoryPath) throws IOException {
        Path source = resolveChild(StorageScope.VAULT, sourceDirectoryPath, itemName, true);
        Path targetDirectory = resolveDirectory(StorageScope.VAULT, targetDirectoryPath);
        Path target = resolveChild(StorageScope.VAULT, targetDirectoryPath, source.getFileName().toString(), false);
        if (targetDirectory.startsWith(source)) {
            throw new StorageAccessException("A directory cannot be moved into itself.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.getFileName().toString());
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    public void delete(String directoryPath, List<String> itemNames) throws IOException {
        for (String itemName : itemNames) {
            Path item = resolveChild(StorageScope.VAULT, directoryPath, itemName, true);
            deleteRecursively(item);
        }
    }

    public void writeZip(StorageScope scope, String directoryPath, List<String> itemNames, OutputStream outputStream)
            throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (String itemName : itemNames) {
                Path item = resolveChild(scope, directoryPath, itemName, true);
                writeZipEntry(item, item.getFileName().toString(), zipOutputStream);
            }
        }
    }

    public void writeSharedZip(String sharedBasePath, String directoryPath, List<String> itemNames,
            OutputStream outputStream) throws IOException {
        Path sharedBase = resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = resolveSharedPath(sharedBase, directoryPath);

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (String itemName : itemNames) {
                validateSingleName(itemName);
                Path item = directory.resolve(itemName).normalize();
                ensureInsideSharedBase(sharedBase, item);
                rejectVaultSystemPath(item);
                ensureExistingPathInsideSharedBase(sharedBase, item);
                writeZipEntry(item, item.getFileName().toString(), zipOutputStream);
            }
        }
    }

    private Path resolveDirectory(StorageScope scope, String requestedPath) throws IOException {
        Path directory = resolve(scope, requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }
        return directory;
    }

    private Path resolveChild(StorageScope scope, String directoryPath, String itemName, boolean mustExist)
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

    private Path resolve(StorageScope scope, String requestedPath) throws IOException {
        Path base = baseFor(scope);
        Path relative = sanitizeRelativePath(requestedPath);
        Path candidate = base.resolve(relative).normalize();
        ensureInsideBase(scope, candidate);
        ensureExistingPathInsideBase(scope, candidate);
        rejectHiddenSystemPath(scope, candidate);
        return candidate;
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

    private Path resolveSharedPath(Path sharedBase, String requestedPath) throws IOException {
        Path relative = sanitizeRelativePath(requestedPath);
        Path candidate = sharedBase.resolve(relative).normalize();
        ensureInsideSharedBase(sharedBase, candidate);
        ensureExistingPathInsideSharedBase(sharedBase, candidate);
        rejectVaultSystemPath(candidate);
        return candidate;
    }

    private Path resolveTrashChild(String trashName) {
        validateSingleName(trashName);
        Path child = trashRoot.resolve(trashName).normalize();
        ensureInsideTrashRoot(child);
        return child;
    }

    private Path resolveRestoreTarget(String vaultPath) throws IOException {
        validateVaultItemPath(vaultPath);
        Path target = root.resolve(sanitizeRelativePath(vaultPath)).normalize();
        ensureInsideBase(StorageScope.VAULT, target);
        rejectHiddenSystemPath(StorageScope.VAULT, target);
        ensureParentInsideBase(StorageScope.VAULT, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.getFileName().toString());
        }
        return target;
    }

    private void validateSingleName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new StorageAccessException("Name is required.");
        }
        if (itemName.contains("/") || itemName.contains("\\") || ".".equals(itemName) || "..".equals(itemName)
                || itemName.contains(":")) {
            throw new StorageAccessException("Invalid name: " + itemName);
        }
    }

    private void validateVaultItemPath(String vaultPath) {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            throw new StorageAccessException("Path is required.");
        }
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
                    .filter(path -> !isHiddenSystemPath(scope, path))
                    .sorted(pathNameComparator())
                    .collect(Collectors.toList())) {
                if (matchesSearchQuery(child, normalizedQuery)) {
                    results.add(toFileItem(baseFor(scope), child));
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

    private Path baseFor(StorageScope scope) {
        return switch (scope) {
            case VAULT -> root;
        };
    }

    private void ensureInsideBase(StorageScope scope, Path candidate) {
        Path base = baseFor(scope);
        if (!candidate.normalize().startsWith(base)) {
            throw new StorageAccessException("Path is outside the allowed storage area.");
        }
    }

    private void ensureExistingPathInsideBase(StorageScope scope, Path candidate) throws IOException {
        Path base = baseFor(scope).toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(base)) {
            throw new StorageAccessException("Path is outside the allowed storage area.");
        }
    }

    private void ensureParentInsideBase(StorageScope scope, Path candidate) throws IOException {
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new StorageAccessException("Invalid target path.");
        }
        ensureExistingPathInsideBase(scope, parent);
    }

    private void ensureInsideSharedBase(Path sharedBase, Path candidate) {
        if (!candidate.normalize().startsWith(sharedBase)) {
            throw new StorageAccessException("Path is outside the shared directory.");
        }
    }

    private void ensureInsideTrashRoot(Path candidate) {
        if (!candidate.normalize().startsWith(trashRoot)) {
            throw new StorageAccessException("Path is outside trash.");
        }
    }

    private void ensureExistingPathInsideSharedBase(Path sharedBase, Path candidate) throws IOException {
        Path realSharedBase = sharedBase.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realSharedBase)) {
            throw new StorageAccessException("Path is outside the shared directory.");
        }
    }

    private String toRelativePath(Path base, Path path) {
        Path relative = base.relativize(path);
        return relative.toString().replace('\\', '/');
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
            String relativePath = toRelativePath(relativeBase, path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return new FileItem(
                    path.getFileName().toString(),
                    relativePath,
                    directory,
                    size,
                    directory ? "-" : ByteSizeFormatter.humanSize(size),
                    MODIFIED_FORMATTER.format(modified),
                    modified,
                    mediaType,
                    isPreviewable(mediaType),
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
        String relativePath = toRelativePath(relativeBase, path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
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
                extensionOf(path, directory),
                isPreviewable(mediaType),
                mediaType.startsWith("video/")
        );
    }

    private long childCount(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(path -> !isHiddenSystemPath(StorageScope.VAULT, path))
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

    private boolean isHiddenSystemPath(StorageScope scope, Path path) {
        return scope == StorageScope.VAULT && isVaultSystemPath(path);
    }

    private boolean isVaultSystemPath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(trashRoot) || normalizedPath.startsWith(metadataRoot);
    }

    private void rejectHiddenSystemPath(StorageScope scope, Path path) throws IOException {
        if (isHiddenSystemPath(scope, path) || isRealVaultSystemPath(path)) {
            throw new NoSuchFileException(toRelativePath(baseFor(scope), path));
        }
    }

    private void rejectVaultSystemPath(Path path) throws IOException {
        if (isVaultSystemPath(path) || isRealVaultSystemPath(path)) {
            throw new NoSuchFileException(toRelativePath(root, path));
        }
    }

    private boolean isRealVaultSystemPath(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return isVaultSystemPath(path.toRealPath());
    }

    private boolean isPreviewable(String mediaType) {
        return mediaType.startsWith("image/")
                || mediaType.startsWith("video/")
                || mediaType.startsWith("text/")
                || mediaType.equals("application/pdf");
    }

    private String safeSubmittedFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new StorageAccessException("Uploaded file name is blank.");
        }
        String cleaned = originalFilename.replace('\\', '/');
        String filename = Path.of(cleaned).getFileName().toString();
        validateSingleName(filename);
        return filename;
    }

    private String validateConfiguredDirectory(String directoryName) {
        validateSingleName(directoryName);
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

    private void writeZipEntry(Path source, String entryName, ZipOutputStream zipOutputStream) throws IOException {
        if (Files.isSymbolicLink(source)) {
            return;
        }
        String normalizedEntryName = entryName.replace('\\', '/');
        if (Files.isDirectory(source)) {
            zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName + "/"));
            zipOutputStream.closeEntry();
            try (Stream<Path> children = Files.list(source)) {
                for (Path child : children.sorted(pathNameComparator()).collect(Collectors.toList())) {
                    writeZipEntry(child, normalizedEntryName + "/" + child.getFileName(), zipOutputStream);
                }
            }
            return;
        }

        zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName));
        try (InputStream inputStream = Files.newInputStream(source)) {
            inputStream.transferTo(zipOutputStream);
        }
        zipOutputStream.closeEntry();
    }
}
