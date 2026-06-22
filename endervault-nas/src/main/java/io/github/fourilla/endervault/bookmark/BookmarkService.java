package io.github.fourilla.endervault.bookmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

@Service
public class BookmarkService {

    private static final TypeReference<List<BookmarkItem>> BOOKMARK_LIST = new TypeReference<>() {
    };
    private static final String RECOVERED_DIRECTORY_TITLE = "Recovered Bookmarks";

    private final JsonRegistry<List<BookmarkItem>> registry;
    private final BookmarkFaviconCacheService faviconCacheService;
    private final BookmarkBulkLinkParser bulkLinkParser = new BookmarkBulkLinkParser();
    private final BookmarkInputNormalizer inputNormalizer = new BookmarkInputNormalizer();
    private final NasProperties nasProperties;
    private final BookmarkMetadataFetcher metadataFetcher;

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
        this.faviconCacheService = new BookmarkFaviconCacheService(
                objectMapper,
                metadataRoot,
                nasProperties.getBookmarks().getFaviconCacheDirectory()
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
        faviconCacheService.initialize();
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
                inputNormalizer.normalizeTitle(title),
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

    public BookmarkItem createLink(String parentId, String title, String url, String note) throws IOException {
        return createLink(parentId, title, url, note, () -> false);
    }

    public BookmarkItem createLink(
            String parentId,
            String title,
            String url,
            String note,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        BookmarkItem link = newLink(parentId, title, url, note);
        checkCanceled(cancellationRequested);
        link = tryApplyRemoteMetadata(link, cancellationRequested);
        checkCanceled(cancellationRequested);
        return addPreparedLink(link);
    }

    private synchronized BookmarkItem newLink(String parentId, String title, String url, String note) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        String normalizedUrl = inputNormalizer.normalizeUrl(url);
        BookmarkInputNormalizer.BookmarkTitleChoice titleChoice =
                inputNormalizer.titleChoice(title, normalizedUrl);
        Instant now = Instant.now();
        return new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.LINK,
                normalizedParentId,
                titleChoice.title(),
                normalizedUrl,
                inputNormalizer.normalizeNote(note),
                titleChoice.source(),
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }

    private synchronized BookmarkItem addPreparedLink(BookmarkItem link) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        normalizeParentId(link.parentId(), bookmarks);
        bookmarks.add(link);
        writeAll(bookmarks);
        return link;
    }

    public List<BookmarkItem> createLinks(String parentId, String bulkText) throws IOException {
        List<BulkLinkInput> inputs = parseBulkLinkInputs(bulkText);
        List<BookmarkItem> created = new ArrayList<>();
        for (BulkLinkInput input : inputs) {
            created.add(createLink(parentId, input.title(), input.url(), ""));
        }
        return List.copyOf(created);
    }

    public List<BulkLinkInput> parseBulkLinkInputs(String bulkText) {
        return bulkLinkParser.parse(
                bulkText,
                inputNormalizer::validUrlLine,
                inputNormalizer::validateBulkInput
        );
    }

    public synchronized BookmarkItem updateDirectory(String id, String title) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.directory()) {
            throw new StorageAccessException("Only bookmark directories can be updated here.");
        }

        BookmarkItem updated = current.withTitle(inputNormalizer.normalizeTitle(title), Instant.now());
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

        String normalizedUrl = inputNormalizer.normalizeUrl(url);
        BookmarkInputNormalizer.BookmarkTitleChoice titleChoice =
                inputNormalizer.titleChoice(title, normalizedUrl);
        boolean urlChanged = !normalizedUrl.equals(current.url());
        BookmarkItem updated = current.withLink(
                titleChoice.title(),
                normalizedUrl,
                note == null ? current.note() : inputNormalizer.normalizeNote(note),
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

    public synchronized String normalizeExistingParentId(String parentId) throws IOException {
        return normalizeParentId(parentId, readAllMutable());
    }

    public synchronized BookmarkFavicon favicon(String id) throws IOException {
        return faviconCacheService.favicon(require(id, readAllMutable()));
    }

    public synchronized List<BookmarkFaviconCacheFile> orphanFaviconCacheFiles() throws IOException {
        return faviconCacheService.orphanFiles(readAllMutable());
    }

    public synchronized void deleteFaviconCacheFile(String fileName) throws IOException {
        faviconCacheService.deleteOrphanFile(fileName, readAllMutable());
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

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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
        return tryApplyRemoteMetadata(bookmark, () -> false);
    }

    private BookmarkItem tryApplyRemoteMetadata(BookmarkItem bookmark, BooleanSupplier cancellationRequested) {
        if (!metadataFetchEnabled() || !bookmark.externalLink()) {
            return bookmark;
        }

        try {
            return fetchAndApplyRemoteMetadata(bookmark, cancellationRequested);
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
        return fetchAndApplyRemoteMetadata(bookmark, () -> false);
    }

    private BookmarkItem fetchAndApplyRemoteMetadata(
            BookmarkItem bookmark,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        checkCanceled(cancellationRequested);
        BookmarkMetadataFetchResult result;
        try {
            result = metadataFetcher.fetch(bookmark.url());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageAccessException("Bookmark metadata fetch was interrupted.");
        }
        checkCanceled(cancellationRequested);

        String nextTitle = bookmark.title();
        BookmarkTitleSource nextTitleSource = bookmark.effectiveTitleSource();
        if (result.hasTitle() && nextTitleSource != BookmarkTitleSource.MANUAL) {
            nextTitle = inputNormalizer.normalizeRemoteTitle(result.title());
            nextTitleSource = BookmarkTitleSource.REMOTE_TITLE;
        }

        String faviconFileName = bookmark.faviconFileName();
        String faviconContentType = bookmark.faviconContentType();
        if (result.hasFavicon()) {
            checkCanceled(cancellationRequested);
            BookmarkMetadataFetchResult.Favicon favicon = result.favicon();
            BookmarkFaviconCacheEntry cachedFavicon = faviconCacheService.cache(favicon);
            checkCanceled(cancellationRequested);
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

    private void checkCanceled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("Bookmark link creation was canceled.");
        }
    }

    private String truncateStatus(String status) {
        String normalized = status == null ? "Unknown error" : status.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200).trim();
    }

    public record BulkLinkInput(String title, String url) {
    }

    public record BookmarkFavicon(Path path, String contentType) {
    }
}
