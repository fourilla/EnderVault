package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

final class StorageListingService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path root;
    private final Path trashRoot;
    private final StoragePathResolver pathResolver;
    private final FileActionRegistry fileActionRegistry;

    StorageListingService(
            Path root,
            Path trashRoot,
            StoragePathResolver pathResolver,
            FileActionRegistry fileActionRegistry
    ) {
        this.root = root;
        this.trashRoot = trashRoot;
        this.pathResolver = pathResolver;
        this.fileActionRegistry = fileActionRegistry;
    }

    DirectoryListing list(StorageScope scope, String requestedPath, FileSort sort, SortDirection direction)
            throws IOException {
        return list(scope, requestedPath, sort, direction, false);
    }

    DirectoryListing list(
            StorageScope scope,
            String requestedPath,
            FileSort sort,
            SortDirection direction,
            boolean showHidden
    )
            throws IOException {
        Path directory = pathResolver.resolveDirectory(scope, requestedPath);
        String currentPath = pathResolver.toRelativePath(pathResolver.baseFor(scope), directory);
        List<FileItem> children;

        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isHiddenSystemPath(scope, path))
                    .filter(path -> showHidden || !isHidden(path))
                    .map(path -> toFileItem(pathResolver.baseFor(scope), path))
                    .sorted(itemComparator(sort, direction))
                    .toList();
        }

        return directoryListing(
                currentPath,
                parentPathOf(currentPath).orElse(null),
                breadcrumbsFor(currentPath),
                children
        );
    }

    DirectoryListing listTrash() throws IOException {
        Files.createDirectories(trashRoot);
        List<FileItem> children;
        try (Stream<Path> stream = Files.list(trashRoot)) {
            children = stream
                    .map(path -> toFileItem(trashRoot, path))
                    .sorted(itemComparator(FileSort.NAME, SortDirection.ASC))
                    .toList();
        }

        return directoryListing("", null, List.of(new Breadcrumb("Trash", "")), children);
    }

    List<FileItem> search(StorageScope scope, String requestedRoot, String query) throws IOException {
        return search(scope, requestedRoot, query, false);
    }

    List<FileItem> search(StorageScope scope, String requestedRoot, String query, boolean showHidden) throws IOException {
        String normalizedQuery = normalizeSearchQuery(query);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        Path searchRoot = pathResolver.resolveDirectory(scope, requestedRoot);
        if (pathResolver.isHiddenSystemPath(scope, searchRoot)) {
            throw new NoSuchFileException(requestedRoot == null ? "" : requestedRoot);
        }
        if (!showHidden && StorageHiddenPolicy.containsHiddenElement(pathResolver.baseFor(scope), searchRoot)) {
            throw new NoSuchFileException(requestedRoot == null ? "" : requestedRoot);
        }

        List<FileItem> results = new ArrayList<>();
        searchRecursively(scope, searchRoot, normalizedQuery, showHidden, results);
        return results.stream()
                .sorted(Comparator.comparing(item -> item.path().toLowerCase(Locale.ROOT)))
                .toList();
    }

    DirectoryListing listSharedDirectory(String sharedBasePath, String requestedPath) throws IOException {
        return listSharedDirectory(sharedBasePath, requestedPath, false);
    }

    DirectoryListing listSharedDirectory(String sharedBasePath, String requestedPath, boolean showHidden)
            throws IOException {
        Path sharedBase = pathResolver.resolveDirectory(StorageScope.VAULT, sharedBasePath);
        Path directory = pathResolver.resolveSharedPath(sharedBase, requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }
        if (!showHidden && StorageHiddenPolicy.containsHiddenElement(sharedBase, directory)) {
            throw new NoSuchFileException(requestedPath == null ? "" : requestedPath);
        }

        String currentPath = pathResolver.toRelativePath(sharedBase, directory);
        List<FileItem> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isVaultSystemPath(path))
                    .filter(path -> showHidden || !isHidden(path))
                    .map(path -> toFileItem(sharedBase, path))
                    .sorted(itemComparator(FileSort.NAME, SortDirection.ASC))
                    .toList();
        }

        return directoryListing(
                currentPath,
                parentPathOf(currentPath).orElse(null),
                breadcrumbsFor(currentPath),
                children
        );
    }

    FileItem describeVaultPath(String vaultPath) throws IOException {
        return toFileItem(root, pathResolver.resolve(StorageScope.VAULT, vaultPath));
    }

    FileDetail detail(StorageScope scope, String vaultPath) throws IOException {
        Path path = pathResolver.resolve(scope, vaultPath);
        return toFileDetail(pathResolver.baseFor(scope), path);
    }

    FileItem describeVaultChild(String directoryPath, String itemName) throws IOException {
        return toFileItem(root, pathResolver.resolveChild(StorageScope.VAULT, directoryPath, itemName, true));
    }

    String mediaType(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        return mediaType == null ? "application/octet-stream" : mediaType;
    }

    FileItem toFileItem(Path relativeBase, Path path) {
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
                    mediaType.startsWith("video/"),
                    isHidden(path)
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read file metadata.", ex);
        }
    }

    private DirectoryListing directoryListing(
            String currentPath,
            String parentPath,
            List<Breadcrumb> breadcrumbs,
            List<FileItem> children
    ) {
        return new DirectoryListing(
                currentPath,
                parentPath,
                breadcrumbs,
                children.stream().filter(FileItem::directory).toList(),
                children.stream().filter(item -> !item.directory()).toList()
        );
    }

    private void searchRecursively(
            StorageScope scope,
            Path directory,
            String normalizedQuery,
            boolean showHidden,
            List<FileItem> results
    ) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path child : stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !pathResolver.isHiddenSystemPath(scope, path))
                    .filter(path -> showHidden || !isHidden(path))
                    .sorted(pathNameComparator())
                    .collect(Collectors.toList())) {
                if (matchesSearchQuery(child, normalizedQuery)) {
                    results.add(toFileItem(pathResolver.baseFor(scope), child));
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    searchRecursively(scope, child, normalizedQuery, showHidden, results);
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
                mediaType.startsWith("video/"),
                isHidden(path)
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

    private boolean isHidden(Path path) {
        try {
            return StorageHiddenPolicy.isHidden(path);
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read hidden file metadata.", ex);
        }
    }
}
