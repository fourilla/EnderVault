package io.github.fourilla.endervault.bookmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
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
    private static final TypeReference<List<BookmarkFaviconCacheEntry>> FAVICON_CACHE_LIST = new TypeReference<>() {
    };
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_NOTE_LENGTH = 1000;
    private static final int MAX_BULK_LINKS = 1000;
    private static final String RECOVERED_DIRECTORY_TITLE = "Recovered Bookmarks";

    private final JsonRegistry<List<BookmarkItem>> registry;
    private final JsonRegistry<List<BookmarkFaviconCacheEntry>> faviconRegistry;
    private final NasProperties nasProperties;
    private final BookmarkMetadataFetcher metadataFetcher;
    private final Path faviconRoot;

    public BookmarkService(
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            BookmarkMetadataFetcher metadataFetcher
    ) {
        this.nasProperties = nasProperties;
        this.metadataFetcher = metadataFetcher;
        NasProperties.Storage storage = nasProperties.getStorage();
        Path metadataRoot = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataDirectory());
        this.registry = new JsonRegistry<>(
                objectMapper,
                metadataRoot.resolve("bookmarks.json"),
                BOOKMARK_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
        this.faviconRegistry = new JsonRegistry<>(
                objectMapper,
                metadataRoot.resolve("bookmark-favicon-cache.json"),
                FAVICON_CACHE_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
        this.faviconRoot = metadataRoot.resolve(nasProperties.getBookmarks().getFaviconCacheDirectory()).normalize();
        if (!faviconRoot.startsWith(metadataRoot)) {
            throw new StorageAccessException("Bookmark favicon cache directory must stay inside metadata storage.");
        }
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
        faviconRegistry.initialize();
        Files.createDirectories(faviconRoot);
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

    public synchronized List<BookmarkItem> storedItems() throws IOException {
        return List.copyOf(readAllMutable());
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
                BookmarkTitleSource.MANUAL,
                null,
                null,
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
        String normalizedUrl = normalizeUrl(url);
        TitleChoice titleChoice = titleChoice(title, normalizedUrl);
        Instant now = Instant.now();
        BookmarkItem link = new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.LINK,
                normalizedParentId,
                titleChoice.title(),
                normalizedUrl,
                normalizeNote(note),
                titleChoice.source(),
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
        link = tryApplyRemoteMetadata(link);
        bookmarks.add(link);
        writeAll(bookmarks);
        return link;
    }

    public synchronized List<BookmarkItem> createLinks(String parentId, String bulkText) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        List<BulkLinkInput> inputs = parseBulkLinks(bulkText);
        if (inputs.isEmpty()) {
            throw new StorageAccessException("Bulk add text is required.");
        }
        if (inputs.size() > MAX_BULK_LINKS) {
            throw new StorageAccessException("Bulk add is limited to " + MAX_BULK_LINKS + " links at a time.");
        }

        Instant now = Instant.now();
        List<BookmarkItem> created = new ArrayList<>();
        for (BulkLinkInput input : inputs) {
            String normalizedUrl = normalizeUrl(input.url());
            TitleChoice titleChoice = titleChoice(input.title(), normalizedUrl);
            BookmarkItem link = new BookmarkItem(
                    UUID.randomUUID().toString(),
                    BookmarkItemType.LINK,
                    normalizedParentId,
                    titleChoice.title(),
                    normalizedUrl,
                    "",
                    titleChoice.source(),
                    null,
                    null,
                    null,
                    null,
                    now,
                    now,
                    null
            );
            created.add(tryApplyRemoteMetadata(link));
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

        String normalizedUrl = normalizeUrl(url);
        TitleChoice titleChoice = titleChoice(title, normalizedUrl);
        boolean urlChanged = !normalizedUrl.equals(current.url());
        BookmarkItem updated = current.withLink(
                titleChoice.title(),
                normalizedUrl,
                note == null ? current.note() : normalizeNote(note),
                titleChoice.source(),
                Instant.now()
        );
        if (urlChanged) {
            updated = updated.withRemoteMetadata(
                    updated.title(),
                    updated.effectiveTitleSource(),
                    null,
                    null,
                    null,
                    null,
                    updated.updatedAt()
            );
        }
        updated = tryApplyRemoteMetadata(updated);
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem refreshMetadata(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.link()) {
            throw new StorageAccessException("Only bookmark links can fetch metadata.");
        }

        BookmarkItem updated = fetchAndApplyRemoteMetadata(current);
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

    public synchronized BookmarkItem moveToRoot(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        BookmarkItem updated = current.withParentId(null, Instant.now());
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem moveToRecoveredDirectory(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        BookmarkItem recoveredDirectory = ensureRecoveredDirectory(bookmarks);
        if (current.id().equals(recoveredDirectory.id())) {
            BookmarkItem updated = current.withParentId(null, Instant.now());
            bookmarks.set(index, updated);
            writeAll(bookmarks);
            return updated;
        }

        BookmarkItem updated = current.withParentId(recoveredDirectory.id(), Instant.now());
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
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

    public synchronized BookmarkFavicon favicon(String id) throws IOException {
        BookmarkItem bookmark = require(id, readAllMutable());
        if (!bookmark.link() || !bookmark.faviconAvailable()) {
            throw new StorageAccessException("Bookmark favicon was not found.");
        }
        Path path = faviconRoot.resolve(bookmark.faviconFileName()).normalize();
        if (!path.startsWith(faviconRoot) || !Files.isRegularFile(path)) {
            throw new StorageAccessException("Bookmark favicon was not found.");
        }
        return new BookmarkFavicon(path, bookmark.faviconContentType());
    }

    public boolean metadataFetchEnabled() {
        return nasProperties.getBookmarks().isMetadataFetchEnabled();
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

    private BookmarkItem ensureRecoveredDirectory(List<BookmarkItem> bookmarks) {
        String titleKey = RECOVERED_DIRECTORY_TITLE.toLowerCase(Locale.ROOT);
        for (BookmarkItem bookmark : bookmarks) {
            if (bookmark.directory()
                    && normalizeId(bookmark.parentId()) == null
                    && titleKey.equals(bookmark.title() == null ? "" : bookmark.title().toLowerCase(Locale.ROOT))) {
                return bookmark;
            }
        }

        Instant now = Instant.now();
        BookmarkItem recoveredDirectory = new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.DIRECTORY,
                null,
                RECOVERED_DIRECTORY_TITLE,
                null,
                null,
                BookmarkTitleSource.MANUAL,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
        bookmarks.add(recoveredDirectory);
        return recoveredDirectory;
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

    private TitleChoice titleChoice(String title, String normalizedUrl) {
        String normalizedTitle = title == null ? "" : title.trim();
        if (!normalizedTitle.isBlank()) {
            return new TitleChoice(normalizeTitle(normalizedTitle), BookmarkTitleSource.MANUAL);
        }
        return new TitleChoice(deriveTitleFromUrl(normalizedUrl), BookmarkTitleSource.URL_DERIVED);
    }

    private String deriveTitleFromUrl(String normalizedUrl) {
        try {
            URI uri = new URI(normalizedUrl);
            String prefix = normalizedUrl.startsWith("/") ? "EnderVault" : uri.getHost();
            String path = uri.getPath();
            String suffix = path == null || path.isBlank() || "/".equals(path)
                    ? ""
                    : " / " + decodePath(path.replaceAll("^/+", "").replaceAll("/+$", ""));
            String title = (prefix == null || prefix.isBlank() ? "Bookmark" : prefix) + suffix;
            return truncateTitle(title);
        } catch (URISyntaxException ex) {
            return "Bookmark";
        }
    }

    private String decodePath(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String normalizeRemoteTitle(String title) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? "Bookmark" : truncateTitle(normalized);
    }

    private String truncateTitle(String title) {
        String normalized = title == null ? "Bookmark" : title.trim();
        if (normalized.length() <= MAX_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_TITLE_LENGTH).trim();
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

    private List<BulkLinkInput> parseBulkLinks(String bulkText) {
        List<String> lines = normalizedBulkLines(bulkText);
        List<BulkLinkInput> inputs = new ArrayList<>();
        String pendingTitle = null;

        for (String line : lines) {
            if (validBookmarkUrlLine(line)) {
                inputs.add(new BulkLinkInput(pendingTitle == null ? "" : pendingTitle, line));
                pendingTitle = null;
                continue;
            }

            if (pendingTitle != null) {
                throw new StorageAccessException("Bulk add has consecutive titles without a URL.");
            }
            pendingTitle = line;
        }

        if (pendingTitle != null) {
            throw new StorageAccessException("Bulk add title must be followed by a URL.");
        }
        return inputs;
    }

    private boolean validBookmarkUrlLine(String line) {
        try {
            normalizeUrl(line);
            return true;
        } catch (StorageAccessException ex) {
            return false;
        }
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
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<BookmarkItem> bookmarks) throws IOException {
        registry.write(List.copyOf(bookmarks));
    }

    private BookmarkItem tryApplyRemoteMetadata(BookmarkItem bookmark) {
        if (!metadataFetchEnabled() || !bookmark.externalLink()) {
            return bookmark;
        }

        try {
            return fetchAndApplyRemoteMetadata(bookmark);
        } catch (IOException | StorageAccessException ex) {
            Instant now = Instant.now();
            return bookmark.withRemoteMetadata(
                    bookmark.title(),
                    bookmark.effectiveTitleSource(),
                    bookmark.faviconFileName(),
                    bookmark.faviconContentType(),
                    now,
                    "FAILED: " + truncateStatus(ex.getMessage()),
                    now
            );
        }
    }

    private BookmarkItem fetchAndApplyRemoteMetadata(BookmarkItem bookmark) throws IOException {
        BookmarkMetadataFetchResult result;
        try {
            result = metadataFetcher.fetch(bookmark.url());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageAccessException("Bookmark metadata fetch was interrupted.");
        }

        String nextTitle = bookmark.title();
        BookmarkTitleSource nextTitleSource = bookmark.effectiveTitleSource();
        if (result.hasTitle() && nextTitleSource != BookmarkTitleSource.MANUAL) {
            nextTitle = normalizeRemoteTitle(result.title());
            nextTitleSource = BookmarkTitleSource.REMOTE_TITLE;
        }

        String faviconFileName = bookmark.faviconFileName();
        String faviconContentType = bookmark.faviconContentType();
        if (result.hasFavicon()) {
            BookmarkMetadataFetchResult.Favicon favicon = result.favicon();
            BookmarkFaviconCacheEntry cachedFavicon = cacheFavicon(favicon);
            faviconFileName = cachedFavicon.fileName();
            faviconContentType = cachedFavicon.contentType();
        }

        Instant now = Instant.now();
        return bookmark.withRemoteMetadata(
                nextTitle,
                nextTitleSource,
                faviconFileName,
                faviconContentType,
                now,
                "OK",
                now
        );
    }

    private BookmarkFaviconCacheEntry cacheFavicon(BookmarkMetadataFetchResult.Favicon favicon) throws IOException {
        Files.createDirectories(faviconRoot);

        String sourceUrl = normalizeFaviconSourceUrl(favicon.sourceUrl());
        List<BookmarkFaviconCacheEntry> entries = new ArrayList<>(faviconRegistry.read());
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
        try {
            Files.write(tempFile, favicon.bytes());
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }

        BookmarkFaviconCacheEntry cachedFavicon = new BookmarkFaviconCacheEntry(
                sourceUrl,
                fileName,
                favicon.contentType(),
                Instant.now()
        );
        entries.add(cachedFavicon);
        faviconRegistry.write(List.copyOf(entries));
        return cachedFavicon;
    }

    private boolean cachedFaviconExists(BookmarkFaviconCacheEntry entry) {
        if (entry == null || entry.fileName() == null || entry.fileName().isBlank()) {
            return false;
        }
        Path target = faviconRoot.resolve(entry.fileName()).normalize();
        return target.startsWith(faviconRoot) && Files.isRegularFile(target);
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

    private String truncateStatus(String status) {
        String normalized = status == null ? "Unknown error" : status.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200).trim();
    }

    private record TitleChoice(String title, BookmarkTitleSource source) {
    }

    private record BulkLinkInput(String title, String url) {
    }

    public record BookmarkFavicon(Path path, String contentType) {
    }
}
