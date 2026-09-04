package io.github.fourilla.endervault.bookmark;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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

    private final JsonRegistry<List<BookmarkItem>> registry;
    private final BookmarkFaviconCacheService faviconCacheService;
    private final BookmarkBulkLinkParser bulkLinkParser = new BookmarkBulkLinkParser();
    private final BookmarkInputNormalizer inputNormalizer = new BookmarkInputNormalizer();
    private final BookmarkTree tree = new BookmarkTree();
    private final NasProperties nasProperties;
    private final OutboundRouteStateService outboundRouteStateService;
    private final BookmarkRemoteMetadataApplier remoteMetadataApplier;

    public BookmarkService(
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            BookmarkMetadataFetcher metadataFetcher,
            OutboundRouteStateService outboundRouteStateService,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.nasProperties = nasProperties;
        this.outboundRouteStateService = outboundRouteStateService;
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
                nasProperties.getBookmarks().getFaviconCacheDirectory(),
                temporaryArtifactRegistry
        );
        this.remoteMetadataApplier = new BookmarkRemoteMetadataApplier(
                inputNormalizer,
                metadataFetcher,
                faviconCacheService,
                this::metadataFetchEnabled,
                outboundRouteStateService::currentRoute
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
        faviconCacheService.initialize();
    }

    public synchronized List<BookmarkItem> list(String parentId, String query) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = tree.normalizeParentId(parentId, bookmarks);
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return bookmarks.stream()
                    .filter(bookmark -> tree.sameParent(bookmark.parentId(), normalizedParentId))
                    .sorted(tree.comparator())
                    .toList();
        }

        Set<String> visibleDirectoryIds = tree.descendantDirectoryIds(bookmarks, normalizedParentId);
        return bookmarks.stream()
                .filter(bookmark -> visibleDirectoryIds.contains(tree.normalizeId(bookmark.parentId())))
                .filter(bookmark -> matches(bookmark, normalizedQuery))
                .sorted(tree.comparator())
                .toList();
    }

    public synchronized List<BookmarkItem> storedItems() throws IOException {
        return List.copyOf(readAllMutable());
    }

    public synchronized BookmarkItem createDirectory(String parentId, String title) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = tree.normalizeParentId(parentId, bookmarks);
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
        return createLink(parentId, title, url, note, cancellationRequested, outboundRouteStateService.currentRoute());
    }

    public BookmarkItem createLink(
            String parentId,
            String title,
            String url,
            String note,
            BooleanSupplier cancellationRequested,
            NetworkRoute networkRoute
    ) throws IOException {
        BookmarkItem link = newLink(parentId, title, url, note);
        checkCanceled(cancellationRequested);
        link = remoteMetadataApplier.tryApply(link, cancellationRequested, networkRoute);
        checkCanceled(cancellationRequested);
        return addPreparedLink(link);
    }

    private synchronized BookmarkItem newLink(String parentId, String title, String url, String note) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        String normalizedParentId = tree.normalizeParentId(parentId, bookmarks);
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
        tree.normalizeParentId(link.parentId(), bookmarks);
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
        int index = tree.indexOf(bookmarks, id);
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
        int index = tree.indexOf(bookmarks, id);
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
        updated = remoteMetadataApplier.tryApply(updated);
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem refreshMetadata(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = tree.indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        if (!current.link()) {
            throw new StorageAccessException("Only bookmark links can fetch metadata.");
        }

        BookmarkItem updated = remoteMetadataApplier.refresh(current);
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem recordOpen(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = tree.indexOf(bookmarks, id);
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
        String normalizedId = tree.normalizeId(id);
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
        BookmarkItem target = tree.require(id, bookmarks);
        Set<String> idsToRemove = target.directory() ? tree.descendantsIncludingSelf(bookmarks, target.id()) : Set.of(target.id());
        bookmarks.removeIf(bookmark -> idsToRemove.contains(bookmark.id()));
        writeAll(bookmarks);
        return target;
    }

    public synchronized BookmarkItem moveToRoot(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = tree.indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        BookmarkItem updated = current.withParentId(null, Instant.now());
        bookmarks.set(index, updated);
        writeAll(bookmarks);
        return updated;
    }

    public synchronized BookmarkItem moveToRecoveredDirectory(String id) throws IOException {
        List<BookmarkItem> bookmarks = readAllMutable();
        int index = tree.indexOf(bookmarks, id);
        BookmarkItem current = bookmarks.get(index);
        BookmarkItem recoveredDirectory = tree.ensureRecoveredDirectory(bookmarks);
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
                .map(tree::normalizeId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> idsToRemove = new java.util.HashSet<>();
        for (BookmarkItem bookmark : bookmarks) {
            if (!requestedIds.contains(bookmark.id())) {
                continue;
            }
            if (bookmark.directory()) {
                idsToRemove.addAll(tree.descendantsIncludingSelf(bookmarks, bookmark.id()));
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
        return tree.currentDirectory(parentId, readAllMutable());
    }

    public synchronized String normalizeExistingParentId(String parentId) throws IOException {
        return tree.normalizeParentId(parentId, readAllMutable());
    }

    public synchronized BookmarkFavicon favicon(String id) throws IOException {
        return faviconCacheService.favicon(tree.require(id, readAllMutable()));
    }

    public synchronized List<BookmarkFaviconCacheFile> orphanFaviconCacheFiles() throws IOException {
        return faviconCacheService.orphanFiles(readAllMutable());
    }

    public synchronized List<BookmarkFaviconTemporaryFile> temporaryFaviconCacheFiles() throws IOException {
        return faviconCacheService.temporaryFiles();
    }

    public synchronized void deleteFaviconCacheFile(String fileName) throws IOException {
        faviconCacheService.deleteOrphanFile(fileName, readAllMutable());
    }

    public boolean metadataFetchEnabled() {
        return nasProperties.getBookmarks().isMetadataFetchEnabled();
    }

    public synchronized List<BookmarkBreadcrumb> breadcrumbs(String parentId) throws IOException {
        return tree.breadcrumbs(parentId, readAllMutable());
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matches(BookmarkItem bookmark, String query) {
        return contains(bookmark.title(), query)
                || contains(bookmark.url(), query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private List<BookmarkItem> readAllMutable() throws IOException {
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<BookmarkItem> bookmarks) throws IOException {
        registry.write(List.copyOf(bookmarks));
    }

    private void checkCanceled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("Bookmark link creation was canceled.");
        }
    }

    public record BulkLinkInput(String title, String url) {
    }

    public record BookmarkFavicon(Path path, String contentType) {
    }
}
