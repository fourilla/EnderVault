package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Path publicRoot;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final String trashFolder;
    private final String metadataFolder;

    public StorageService(NasProperties nasProperties) {
        NasProperties.Storage storage = nasProperties.getStorage();
        this.root = storage.getRoot().toAbsolutePath().normalize();
        this.publicRoot = root.resolve(validateConfiguredFolder(storage.getPublicFolder())).normalize();
        this.trashFolder = validateConfiguredFolder(storage.getTrashFolder());
        this.metadataFolder = validateConfiguredFolder(storage.getMetadataFolder());
        this.trashRoot = root.resolve(trashFolder).normalize();
        this.metadataRoot = root.resolve(metadataFolder).normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(publicRoot);
        Files.createDirectories(trashRoot);
        Files.createDirectories(metadataRoot);
    }

    public DirectoryListing list(StorageScope scope, String requestedPath) throws IOException {
        Path directory = resolveDirectory(scope, requestedPath);
        String currentPath = toRelativePath(baseFor(scope), directory);
        List<FileItem> children;

        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !isHiddenSystemPath(scope, path))
                    .sorted(itemComparator())
                    .map(path -> toFileItem(scope, path))
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

    public Path resolveFile(StorageScope scope, String directoryPath, String fileName) throws IOException {
        Path file = resolveChild(scope, directoryPath, fileName, true);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(fileName);
        }
        return file;
    }

    public String mediaType(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        return mediaType == null ? "application/octet-stream" : mediaType;
    }

    public void upload(String directoryPath, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return;
        }
        String filename = safeSubmittedFilename(file);
        Path target = resolveChild(StorageScope.VAULT, directoryPath, filename, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(filename);
        }
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target);
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

    private void validateSingleName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new StorageAccessException("Name is required.");
        }
        if (itemName.contains("/") || itemName.contains("\\") || ".".equals(itemName) || "..".equals(itemName)
                || itemName.contains(":")) {
            throw new StorageAccessException("Invalid name: " + itemName);
        }
    }

    private Path baseFor(StorageScope scope) {
        return switch (scope) {
            case VAULT -> root;
            case PUBLIC -> publicRoot;
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

    private FileItem toFileItem(StorageScope scope, Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            String mediaType = directory ? "folder" : mediaType(path);
            long size = directory ? 0L : Files.size(path);
            String relativePath = toRelativePath(baseFor(scope), path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return new FileItem(
                    path.getFileName().toString(),
                    relativePath,
                    directory,
                    size,
                    directory ? "-" : humanSize(size),
                    MODIFIED_FORMATTER.format(modified),
                    mediaType,
                    isPreviewable(mediaType),
                    mediaType.startsWith("video/")
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read file metadata.", ex);
        }
    }

    private Comparator<Path> itemComparator() {
        return Comparator
                .comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private boolean isHiddenSystemPath(StorageScope scope, Path path) {
        if (scope != StorageScope.VAULT || !path.getParent().equals(root)) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.equals(trashFolder) || name.equals(metadataFolder);
    }

    private boolean isPreviewable(String mediaType) {
        return mediaType.startsWith("image/")
                || mediaType.startsWith("video/")
                || mediaType.startsWith("text/")
                || mediaType.equals("application/pdf");
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        do {
            value = value / 1024;
            unitIndex++;
        } while (value >= 1024 && unitIndex < units.length - 1);
        return "%.1f %s".formatted(value, units[unitIndex]);
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

    private String validateConfiguredFolder(String folderName) {
        validateSingleName(folderName);
        return folderName;
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
                for (Path child : children.sorted(itemComparator()).collect(Collectors.toList())) {
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

