package io.github.fourilla.endervault.bookmark;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class BookmarkFaviconCacheService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final TypeReference<List<BookmarkFaviconCacheEntry>> CACHE_LIST = new TypeReference<>() {
    };

    private final JsonRegistry<List<BookmarkFaviconCacheEntry>> registry;
    private final Path faviconRoot;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    BookmarkFaviconCacheService(
            ObjectMapper objectMapper,
            Path metadataRoot,
            String cacheDirectory,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.registry = new JsonRegistry<>(
                objectMapper,
                metadataRoot.resolve("bookmark-favicon-cache.json"),
                CACHE_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
        this.faviconRoot = metadataRoot.resolve(cacheDirectory).normalize();
        if (!faviconRoot.startsWith(metadataRoot)) {
            throw new StorageAccessException("Bookmark favicon cache directory must stay inside metadata storage.");
        }
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    void initialize() throws IOException {
        registry.initialize();
        Files.createDirectories(faviconRoot);
    }

    BookmarkService.BookmarkFavicon favicon(BookmarkItem bookmark) {
        if (bookmark == null || !bookmark.link() || !bookmark.faviconAvailable()) {
            throw new StorageAccessException("Bookmark favicon was not found.");
        }
        String fileName = cleanFaviconFileName(bookmark.faviconFileName());
        if (fileName == null) {
            throw new StorageAccessException("Bookmark favicon was not found.");
        }
        Path path = faviconRoot.resolve(fileName).normalize();
        if (!path.startsWith(faviconRoot) || !Files.isRegularFile(path)) {
            throw new StorageAccessException("Bookmark favicon was not found.");
        }
        return new BookmarkService.BookmarkFavicon(path, bookmark.faviconContentType());
    }

    List<BookmarkFaviconCacheFile> orphanFiles(List<BookmarkItem> bookmarks) throws IOException {
        Set<String> referencedFileNames = referencedFaviconFileNames(bookmarks);
        List<BookmarkFaviconCacheEntry> registeredEntries = registry.read();
        Set<String> registeredFileNames = new HashSet<>();
        Set<String> reportedFileNames = new HashSet<>();
        List<BookmarkFaviconCacheFile> orphanFiles = new ArrayList<>();

        for (BookmarkFaviconCacheEntry entry : registeredEntries) {
            String fileName = cleanFaviconFileName(entry.fileName());
            if (fileName == null) {
                continue;
            }
            registeredFileNames.add(fileName);
            if (!referencedFileNames.contains(fileName) && reportedFileNames.add(fileName)) {
                orphanFiles.add(toFaviconCacheFile(fileName, true));
            }
        }

        if (Files.isDirectory(faviconRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.list(faviconRoot)) {
                for (Path path : paths
                        .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                        .filter(candidate -> !Files.isSymbolicLink(candidate))
                        .toList()) {
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith(".tmp")
                            || referencedFileNames.contains(fileName)
                            || !reportedFileNames.add(fileName)) {
                        continue;
                    }
                    orphanFiles.add(toFaviconCacheFile(fileName, registeredFileNames.contains(fileName)));
                }
            }
        }

        return orphanFiles.stream().sorted().toList();
    }

    List<BookmarkFaviconTemporaryFile> temporaryFiles() throws IOException {
        if (!Files.isDirectory(faviconRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(faviconRoot)) {
            return paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> !Files.isSymbolicLink(candidate))
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".tmp"))
                    .map(this::toTemporaryFile)
                    .sorted()
                    .toList();
        }
    }

    void deleteOrphanFile(String fileName, List<BookmarkItem> bookmarks) throws IOException {
        String normalizedFileName = requireFaviconFileName(fileName);
        if (referencedFaviconFileNames(bookmarks).contains(normalizedFileName)) {
            throw new StorageAccessException("Bookmark favicon cache is still referenced.");
        }

        Path target = faviconRoot.resolve(normalizedFileName).normalize();
        if (!target.startsWith(faviconRoot)) {
            throw new StorageAccessException("Bookmark favicon cache path is invalid.");
        }
        if (temporaryArtifactRegistry.isActive(target)) {
            throw new StorageAccessException("Bookmark favicon temporary file is still in use.");
        }

        List<BookmarkFaviconCacheEntry> entries = new ArrayList<>(registry.read());
        boolean changed = entries.removeIf(entry -> normalizedFileName.equals(cleanFaviconFileName(entry.fileName())));
        if (changed) {
            registry.write(List.copyOf(entries));
        }

        Files.deleteIfExists(target);
    }

    BookmarkFaviconCacheEntry cache(BookmarkMetadataFetchResult.Favicon favicon) throws IOException {
        Files.createDirectories(faviconRoot);

        String sourceUrl = normalizeFaviconSourceUrl(favicon.sourceUrl());
        List<BookmarkFaviconCacheEntry> entries = new ArrayList<>(registry.read());
        BookmarkFaviconCacheEntry existing = entries.stream()
                .filter(entry -> sourceUrl.equals(entry.sourceUrl()))
                .findFirst()
                .orElse(null);
        if (existing != null && cachedFaviconExists(existing)) {
            return existing;
        }
        if (existing != null) {
            entries.removeIf(entry -> sourceUrl.equals(entry.sourceUrl()));
        }

        String fileName = newFaviconFileName(favicon.extension());
        Path target = faviconRoot.resolve(fileName).normalize();
        if (!target.startsWith(faviconRoot)) {
            throw new StorageAccessException("Bookmark favicon cache path is invalid.");
        }

        Path tempFile = Files.createTempFile(faviconRoot, "favicon-", ".tmp");
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                tempFile,
                TemporaryArtifactType.BOOKMARK_FAVICON,
                fileName
        );
        try {
            Files.write(tempFile, favicon.bytes());
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } finally {
                registration.close();
            }
        }

        BookmarkFaviconCacheEntry cachedFavicon = new BookmarkFaviconCacheEntry(
                sourceUrl,
                fileName,
                favicon.contentType(),
                Instant.now()
        );
        entries.add(cachedFavicon);
        registry.write(List.copyOf(entries));
        return cachedFavicon;
    }

    private boolean cachedFaviconExists(BookmarkFaviconCacheEntry entry) {
        if (entry == null || entry.fileName() == null || entry.fileName().isBlank()) {
            return false;
        }
        Path target = faviconRoot.resolve(entry.fileName()).normalize();
        return target.startsWith(faviconRoot) && Files.isRegularFile(target);
    }

    private Set<String> referencedFaviconFileNames(List<BookmarkItem> bookmarks) {
        Set<String> fileNames = new HashSet<>();
        for (BookmarkItem bookmark : bookmarks) {
            if (bookmark == null || !bookmark.faviconAvailable()) {
                continue;
            }
            String fileName = cleanFaviconFileName(bookmark.faviconFileName());
            if (fileName != null) {
                fileNames.add(fileName);
            }
        }
        return fileNames;
    }

    private BookmarkFaviconCacheFile toFaviconCacheFile(String fileName, boolean registered) {
        Path target = faviconRoot.resolve(fileName).normalize();
        try {
            long size = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : 0L;
            Instant modified = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    ? Files.getLastModifiedTime(target).toInstant()
                    : null;
            return new BookmarkFaviconCacheFile(
                    fileName,
                    size,
                    ByteSizeFormatter.humanSize(size),
                    modified,
                    modified == null ? "missing file" : MODIFIED_FORMATTER.format(modified),
                    registered
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read bookmark favicon cache metadata.", ex);
        }
    }

    private BookmarkFaviconTemporaryFile toTemporaryFile(Path path) {
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String activeOperation = temporaryArtifactRegistry.find(path)
                    .map(artifact -> artifact.type().label())
                    .orElse(null);
            return new BookmarkFaviconTemporaryFile(
                    path.getFileName().toString(),
                    size,
                    ByteSizeFormatter.humanSize(size),
                    modified,
                    MODIFIED_FORMATTER.format(modified),
                    activeOperation != null,
                    activeOperation
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read bookmark favicon temporary metadata.", ex);
        }
    }

    private String requireFaviconFileName(String fileName) {
        String normalized = cleanFaviconFileName(fileName);
        if (normalized == null) {
            throw new StorageAccessException("Bookmark favicon cache file name is invalid.");
        }
        return normalized;
    }

    private String cleanFaviconFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (normalized.isBlank()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.equals(".")
                || normalized.equals("..")) {
            return null;
        }
        return normalized;
    }

    private String normalizeFaviconSourceUrl(String sourceUrl) {
        String normalized = sourceUrl == null ? "" : sourceUrl.trim();
        return normalized.isBlank() ? "unknown:" + UUID.randomUUID() : normalized;
    }

    private String newFaviconFileName(String extension) {
        String safeExtension = safeFaviconExtension(extension);
        String fileName;
        do {
            fileName = UUID.randomUUID() + "." + safeExtension;
        } while (Files.exists(faviconRoot.resolve(fileName).normalize()));
        return fileName;
    }

    private String safeFaviconExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isBlank()) {
            return "ico";
        }
        if (normalized.length() > 12) {
            return normalized.substring(0, 12);
        }
        return normalized;
    }
}
