package io.github.fourilla.endervault.bookmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BookmarkService {

    private static final TypeReference<List<BookmarkItem>> BOOKMARK_LIST = new TypeReference<>() {
    };
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_NOTE_LENGTH = 1000;
    private static final int MAX_BULK_LINKS = 1000;

    private final ObjectMapper objectMapper;
    private final Path registryFile;

    public BookmarkService(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.objectMapper = objectMapper;
        NasProperties.Storage storage = nasProperties.getStorage();
        this.registryFile = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataDirectory())
                .resolve("bookmarks.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        if (!Files.exists(registryFile)) {
            writeAll(List.of());
        }
    }

    public synchronized List<BookmarkItem> list(String parentId, String query) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return bookmarks.stream()
                    .filter(bookmark -> sameParent(bookmark.parentId(), normalizedParentId))
                    .sorted(bookmarkComparator())
                    .toList();
        }

        Set<String> visibleDirectoryIds = descendantDirectoryIds(bookmarks, normalizedParentId);
        return bookmarks.stream()
                .filter(bookmark -> visibleDirectoryIds.contains(normalizeId(bookmark.parentId())))
                .filter(bookmark -> matches(bookmark, normalizedQuery))
                .sorted(bookmarkComparator())
                .toList();
    }

    public synchronized BookmarkItem createDirectory(String parentId, String title) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        Instant now = Instant.now();
        BookmarkItem directory = new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.DIRECTORY,
                normalizedParentId,
                normalizeTitle(title),
                null,
                null,
                now,
                now,
                null
        );
        bookmarks.add(directory);
        writeAll(bookmarks);
        return directory;
    }

    public synchronized BookmarkItem createLink(String parentId, String title, String url, String note) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        Instant now = Instant.now();
        BookmarkItem link = new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.LINK,
                normalizedParentId,
                normalizeTitle(title),
                normalizeUrl(url),
                normalizeNote(note),
                now,
                now,
                null
        );
        bookmarks.add(link);
        writeAll(bookmarks);
        return link;
    }

    public synchronized List<BookmarkItem> createLinks(String parentId, String bulkText) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        List<String> lines = normalizedBulkLines(bulkText);
        if (lines.isEmpty()) {
            throw new StorageAccessException("Bulk add text is required.");
        }
        if (lines.size() % 2 != 0) {
            throw new StorageAccessException("Bulk add expects title and URL pairs.");
        }
        int linkCount = lines.size() / 2;
        if (linkCount > MAX_BULK_LINKS) {
            throw new StorageAccessException("Bulk add is limited to " + MAX_BULK_LINKS + " links at a time.");
        }

        Instant now = Instant.now();
        List<BookmarkItem> created = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) {
            created.add(new BookmarkItem(
                    UUID.randomUUID().toString(),
                    BookmarkItemType.LINK,
                    normalizedParentId,
                    normalizeTitle(lines.get(i)),
                    normalizeUrl(lines.get(i + 1)),
                    "",
                    now,
                    now,
                    null
            ));
        }

        bookmarks.addAll(created);
        writeAll(bookmarks);
        return List.copyOf(created);
    }

    public synchronized BookmarkItem updateDirectory(String id, String title) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.directory()) {
            throw new StorageAccessException("Only bookmark directories can be updated here.");
        }

        BookmarkItem updated = current.withTitle(normalizeTitle(title), Instant.now());
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem updateLink(String id, String title, String url, String note) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.link()) {
            throw new StorageAccessException("Only bookmark links can be updated here.");
        }

        BookmarkItem updated = current.withLink(
                normalizeTitle(title),
                normalizeUrl(url),
                note == null ? current.note() : normalizeNote(note),
                Instant.now()
        );
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem recordOpen(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.link()) {
            throw new StorageAccessException("Only bookmark links can be opened.");
        }

        BookmarkItem updated = current.withLastOpenedAt(Instant.now());
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem find(String id) throws IOException {
        String normalizedId = normalizeId(id);
        if (normalizedId == null) {
            return null;
        }
        return readAllMutable().stream()
                .filter(bookmark -> bookmark.id().equals(normalizedId))
                .findFirst()
                .orElse(null);
    }

    public synchronized BookmarkItem delete(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        BookmarkItem target = require(id, bookmarks);
        Set<String> idsToRemove = target.directory() ? descendantsIncludingSelf(bookmarks, target.id()) : Set.of(target.id());
        bookmarks.removeIf(bookmark -> idsToRemove.contains(bookmark.id()));
        writeAll(bookmarks);
        return target;
    }

    public synchronized int deleteAll(List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        List<BookmarkItem> bookmarks = readAllMutable();
        Set<String> requestedIds = ids.stream()
                .map(this::normalizeId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> idsToRemove = new HashSet<>();
        for (BookmarkItem bookmark : bookmarks) {
            if (!requestedIds.contains(bookmark.id())) {
                continue;
            }
            if (bookmark.directory()) {
                idsToRemove.addAll(descendantsIncludingSelf(bookmarks, bookmark.id()));
            } else {
                idsToRemove.add(bookmark.id());
            }
        }
        if (idsToRemove.isEmpty()) {
            return 0;
        }

        int originalSize = bookmarks.size();
        bookmarks.removeIf(bookmark -> idsToRemove.contains(bookmark.id()));
        writeAll(bookmarks);
        return originalSize - bookmarks.size();
    }

    public synchronized BookmarkItem currentDirectory(String parentId) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        if (normalizedParentId == null) {
            return null;
        }
        BookmarkItem directory = require(normalizedParentId, bookmarks);
        if (!directory.directory()) {
            throw new StorageAccessException("Bookmark directory not found.");
        }
        return directory;
    }

    public synchronized List<BookmarkBreadcrumb> breadcrumbs(String parentId) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        List<BookmarkBreadcrumb> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(new BookmarkBreadcrumb(null, "Bookmarks"));
        if (normalizedParentId == null) {
            return List.copyOf(breadcrumbs);
        }

        List<BookmarkBreadcrumb> ancestors = new ArrayList<>();
        String cursor = normalizedParentId;
        Set<String> seen = new HashSet<>();
        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw new StorageAccessException("Bookmark directory tree is invalid.");
            }
            BookmarkItem directory = require(cursor, bookmarks);
            if (!directory.directory()) {
                throw new StorageAccessException("Bookmark directory not found.");
            }
            ancestors.add(new BookmarkBreadcrumb(directory.id(), directory.title()));
            cursor = normalizeId(directory.parentId());
        }
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            breadcrumbs.add(ancestors.get(i));
        }
        return List.copyOf(breadcrumbs);
    }

    private int indexOf(List<BookmarkItem> bookmarks, String id) {
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new StorageAccessException("Bookmark item not found.");
    }

    private BookmarkItem require(String id, List<BookmarkItem> bookmarks) {
        return bookmarks.stream()
                .filter(bookmark -> bookmark.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new StorageAccessException("Bookmark item not found."));
    }

    private String normalizeParentId(String parentId, List<BookmarkItem> bookmarks) {
        String normalizedParentId = normalizeId(parentId);
        if (normalizedParentId == null) {
            return null;
        }
        BookmarkItem parent = require(normalizedParentId, bookmarks);
        if (!parent.directory()) {
            throw new StorageAccessException("Bookmark parent must be a directory.");
        }
        return normalizedParentId;
    }

    private String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isBlank()) {
            throw new StorageAccessException("Bookmark title is required.");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new StorageAccessException("Bookmark title is too long.");
        }
        return normalized;
    }

    private String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        if (normalized.isBlank()) {
            throw new StorageAccessException("Bookmark URL is required.");
        }
        if (normalized.length() > MAX_URL_LENGTH) {
            throw new StorageAccessException("Bookmark URL is too long.");
        }
        try {
            URI uri = new URI(normalized);
            if (normalized.startsWith("/") && !normalized.startsWith("//")) {
                if (uri.getScheme() != null || uri.getHost() != null || containsControlOrBackslash(normalized)) {
                    throw new StorageAccessException("Bookmark URL is invalid.");
                }
                return uri.toString();
            }

            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new StorageAccessException("Bookmark URL must use http or https.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new StorageAccessException("Bookmark URL host is required.");
            }
            if (uri.getUserInfo() != null) {
                throw new StorageAccessException("Bookmark URL must not contain user info.");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw new StorageAccessException("Bookmark URL is invalid.", ex);
        }
    }

    private boolean containsControlOrBackslash(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || ch == '\\') {
                return true;
            }
        }
        return false;
    }

    private String normalizeNote(String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.length() > MAX_NOTE_LENGTH) {
            throw new StorageAccessException("Bookmark note is too long.");
        }
        return normalized;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizedBulkLines(String bulkText) {
        if (bulkText == null || bulkText.isBlank()) {
            return List.of();
        }
        return bulkText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    private boolean sameParent(String candidateParentId, String parentId) {
        String normalizedCandidate = normalizeId(candidateParentId);
        return parentId == null ? normalizedCandidate == null : parentId.equals(normalizedCandidate);
    }

    private boolean matches(BookmarkItem bookmark, String query) {
        return contains(bookmark.title(), query)
                || contains(bookmark.url(), query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private Set<String> descendantDirectoryIds(List<BookmarkItem> bookmarks, String parentId) {
        Set<String> directoryIds = new HashSet<>();
        directoryIds.add(parentId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BookmarkItem bookmark : bookmarks) {
                if (bookmark.directory()
                        && !directoryIds.contains(bookmark.id())
                        && directoryIds.contains(normalizeId(bookmark.parentId()))) {
                    directoryIds.add(bookmark.id());
                    changed = true;
                }
            }
        }
        return directoryIds;
    }

    private Set<String> descendantsIncludingSelf(List<BookmarkItem> bookmarks, String id) {
        Set<String> ids = new HashSet<>();
        ids.add(id);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BookmarkItem bookmark : bookmarks) {
                if (!ids.contains(bookmark.id()) && ids.contains(normalizeId(bookmark.parentId()))) {
                    ids.add(bookmark.id());
                    changed = true;
                }
            }
        }
        return ids;
    }

    private Comparator<BookmarkItem> bookmarkComparator() {
        return Comparator.comparing(BookmarkItem::directory).reversed()
                .thenComparing(bookmark -> bookmark.title().toLowerCase(Locale.ROOT))
                .thenComparing(BookmarkItem::id);
    }

    private List<BookmarkItem> readAllMutable() throws IOException {
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(registryFile.toFile(), BOOKMARK_LIST));
    }

    private void writeAll(List<BookmarkItem> bookmarks) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), bookmarks);
        try {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
