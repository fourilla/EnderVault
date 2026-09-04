package io.github.fourilla.endervault.favorite;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class FavoriteService {

    private static final TypeReference<List<FavoriteItem>> FAVORITE_LIST = new TypeReference<>() {
    };
    private static final String BOOKMARK_KEY_PREFIX = "bookmark:";

    private final StorageService storageService;
    private final BookmarkService bookmarkService;
    private final JsonRegistry<List<FavoriteItem>> registry;

    public FavoriteService(
            StorageService storageService,
            BookmarkService bookmarkService,
            ObjectMapper objectMapper,
            NasProperties nasProperties
    ) {
        this.storageService = storageService;
        this.bookmarkService = bookmarkService;
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("favorites.json"),
                FAVORITE_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<FavoriteItem> list() throws IOException {
        return list(true);
    }

    public synchronized List<FavoriteItem> list(boolean showHidden) throws IOException {
        return readAllMutable().stream()
                .map(this::enrich)
                .filter(favorite -> showHidden || !containsHiddenVaultPath(favorite))
                .toList();
    }

    public synchronized List<FavoriteDisplayItem> listDisplay(boolean showHidden) throws IOException {
        return readAllMutable().stream()
                .map(this::enrich)
                .map(this::display)
                .filter(favorite -> showHidden || !favorite.hidden())
                .toList();
    }

    public synchronized List<FavoriteItem> listExisting() throws IOException {
        return listExisting(true);
    }

    public synchronized List<FavoriteItem> listExisting(boolean showHidden) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        return favorites.stream()
                .filter(this::exists)
                .map(this::enrich)
                .filter(favorite -> showHidden || !containsHiddenVaultPath(favorite))
                .toList();
    }

    public synchronized List<FavoriteDisplayItem> listExistingDisplay(boolean showHidden) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        return favorites.stream()
                .filter(this::exists)
                .map(this::enrich)
                .map(this::display)
                .filter(favorite -> showHidden || !favorite.hidden())
                .toList();
    }

    public synchronized Set<String> favoritePaths() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (FavoriteItem favorite : readAllMutable()) {
            if (!favorite.bookmark()) {
                paths.add(favorite.path());
            }
        }
        return Set.copyOf(paths);
    }

    public synchronized boolean isFavorite(String vaultPath) throws IOException {
        return readAllMutable().stream()
                .filter(favorite -> !favorite.bookmark())
                .anyMatch(favorite -> favorite.path().equals(vaultPath));
    }

    public synchronized Set<String> favoriteBookmarkIds() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (FavoriteItem favorite : readAllMutable()) {
            if (favorite.bookmark()) {
                ids.add(favorite.bookmarkId());
            }
        }
        return Set.copyOf(ids);
    }

    public synchronized boolean isBookmarkFavorite(String bookmarkId) throws IOException {
        String key = bookmarkKey(bookmarkId);
        return readAllMutable().stream().anyMatch(favorite -> favorite.path().equals(key));
    }

    public synchronized FavoriteItem toggle(String vaultPath) throws IOException {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            throw new StorageAccessException("Path is required.");
        }

        FileItem item = storageService.describeVaultPath(vaultPath);
        List<FavoriteItem> favorites = readAllMutable();
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).path().equals(item.path())) {
                favorites.remove(i);
                writeAll(favorites);
                return null;
            }
        }

        FavoriteItem favorite = new FavoriteItem(
                item.path(),
                item.directory() ? FavoriteTargetType.DIRECTORY : FavoriteTargetType.FILE,
                Instant.now(),
                null
        );
        favorites.add(favorite);
        writeAll(favorites);
        return favorite;
    }

    public synchronized FavoriteItem toggleBookmark(String bookmarkId) throws IOException {
        BookmarkItem bookmark = requireBookmark(bookmarkId);
        String key = bookmarkKey(bookmark.id());
        List<FavoriteItem> favorites = readAllMutable();
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).path().equals(key)) {
                favorites.remove(i);
                writeAll(favorites);
                return null;
            }
        }

        FavoriteItem favorite = new FavoriteItem(
                key,
                bookmark.directory() ? FavoriteTargetType.BOOKMARK_DIRECTORY : FavoriteTargetType.BOOKMARK_LINK,
                Instant.now(),
                bookmark.title()
        );
        favorites.add(favorite);
        writeAll(favorites);
        return favorite;
    }

    public synchronized void remove(String vaultPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = favorites.removeIf(favorite -> favorite.path().equals(vaultPath));
        if (changed) {
            writeAll(favorites);
        }
    }

    public synchronized void move(String vaultPath, String direction) throws IOException {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            return;
        }

        List<FavoriteItem> favorites = readAllMutable();
        int index = indexOf(favorites, vaultPath);
        if (index < 0) {
            return;
        }

        int targetIndex = "up".equals(direction) ? index - 1 : index + 1;
        if (targetIndex < 0 || targetIndex >= favorites.size()) {
            return;
        }

        FavoriteItem favorite = favorites.get(index);
        favorites.set(index, favorites.get(targetIndex));
        favorites.set(targetIndex, favorite);
        writeAll(favorites);
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < favorites.size(); i++) {
            FavoriteItem favorite = favorites.get(i);
            if (favorite.bookmark()) {
                continue;
            }
            if (matchesPathOrDescendant(favorite.path(), oldPath)) {
                favorites.set(i, favorite.withPath(rebasedPath(favorite.path(), oldPath, newPath)));
                changed = true;
            }
        }
        if (changed) {
            writeAll(favorites);
        }
    }

    public synchronized void removeVaultPath(String vaultPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = favorites.removeIf(favorite -> !favorite.bookmark()
                && matchesPathOrDescendant(favorite.path(), vaultPath));
        if (changed) {
            writeAll(favorites);
        }
    }

    public synchronized boolean targetExists(FavoriteItem favorite) {
        return exists(favorite);
    }

    public synchronized boolean hidden(FavoriteItem favorite) {
        return containsHiddenVaultPath(favorite);
    }

    private boolean exists(FavoriteItem favorite) {
        if (favorite.bookmark()) {
            try {
                return bookmarkService.find(favorite.bookmarkId()) != null;
            } catch (IOException ex) {
                return false;
            }
        }
        try {
            storageService.describeVaultPath(favorite.path());
            return true;
        } catch (IOException | StorageAccessException ex) {
            return false;
        }
    }

    private boolean containsHiddenVaultPath(FavoriteItem favorite) {
        if (favorite.bookmark()) {
            return false;
        }
        try {
            return storageService.vaultPathContainsHiddenElement(favorite.path());
        } catch (IOException | StorageAccessException ex) {
            return false;
        }
    }

    private FavoriteDisplayItem display(FavoriteItem favorite) {
        return new FavoriteDisplayItem(favorite, containsHiddenVaultPath(favorite));
    }

    private int indexOf(List<FavoriteItem> favorites, String vaultPath) {
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).path().equals(vaultPath)) {
                return i;
            }
        }
        return -1;
    }

    private FavoriteItem enrich(FavoriteItem favorite) {
        if (!favorite.bookmark()) {
            return favorite;
        }
        try {
            BookmarkItem bookmark = bookmarkService.find(favorite.bookmarkId());
            if (bookmark == null) {
                return favorite;
            }
            return favorite.withBookmarkTitle(
                    bookmark.title(),
                    bookmark.directory() ? FavoriteTargetType.BOOKMARK_DIRECTORY : FavoriteTargetType.BOOKMARK_LINK
            );
        } catch (IOException ex) {
            return favorite;
        }
    }

    private BookmarkItem requireBookmark(String bookmarkId) throws IOException {
        BookmarkItem bookmark = bookmarkService.find(bookmarkId);
        if (bookmark == null) {
            throw new StorageAccessException("Bookmark was not found.");
        }
        return bookmark;
    }

    private String bookmarkKey(String bookmarkId) {
        String normalizedId = bookmarkId == null ? "" : bookmarkId.trim();
        if (normalizedId.isBlank()) {
            throw new StorageAccessException("Bookmark id is required.");
        }
        return BOOKMARK_KEY_PREFIX + normalizedId;
    }

    private List<FavoriteItem> readAllMutable() throws IOException {
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<FavoriteItem> favorites) throws IOException {
        registry.write(List.copyOf(favorites));
    }

    private boolean matchesPathOrDescendant(String candidatePath, String basePath) {
        return candidatePath.equals(basePath) || candidatePath.startsWith(basePath + "/");
    }

    private String rebasedPath(String candidatePath, String oldPath, String newPath) {
        if (candidatePath.equals(oldPath)) {
            return newPath;
        }
        return newPath + candidatePath.substring(oldPath.length());
    }
}
