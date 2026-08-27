package io.github.fourilla.endervault.web.file.browser;

import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.file.FilePage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class FileBrowserQueryService {

    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final FileBrowserPreferences preferences;

    public FileBrowserQueryService(
            StorageService storageService,
            FavoriteService favoriteService,
            RecentService recentService,
            FileBrowserPreferences preferences
    ) {
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.preferences = preferences;
    }

    public FileBrowserResult browse(
            String path,
            String view,
            String sort,
            String direction,
            String hidden,
            Integer page,
            Integer pageSize,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        FileBrowserPreferences.Values resolved = preferences.resolveFiles(
                request,
                response,
                view,
                sort,
                direction,
                hidden,
                pageSize
        );
        DirectoryListing listing = list(path, resolved);
        recentService.recordVaultPath(listing.path());
        return result(
                FileBrowserResult.Mode.BROWSE,
                listing,
                listing.directories(),
                listing.files(),
                page,
                resolved,
                "",
                false
        );
    }

    public FileBrowserResult search(
            String path,
            String query,
            String view,
            String sort,
            String direction,
            String hidden,
            Integer page,
            Integer pageSize,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        FileBrowserPreferences.Values resolved = preferences.resolveFiles(
                request,
                response,
                view,
                sort,
                direction,
                hidden,
                pageSize
        );
        String normalizedQuery = query == null ? "" : query.trim();
        DirectoryListing context = list(path, resolved);
        List<FileItem> results = normalizedQuery.isEmpty()
                ? List.of()
                : storageService.search(
                        StorageScope.VAULT,
                        context.path(),
                        normalizedQuery,
                        resolved.sort(),
                        resolved.direction(),
                        resolved.showHidden()
                );
        return result(
                FileBrowserResult.Mode.SEARCH,
                context,
                List.of(),
                results,
                page,
                resolved,
                normalizedQuery,
                !normalizedQuery.isEmpty()
        );
    }

    public FileBrowserResult readOnly(
            String path,
            String sort,
            String direction,
            String hidden,
            Integer page,
            Integer pageSize,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        FileBrowserPreferences.Values resolved = preferences.resolveReadOnly(
                request,
                response,
                sort,
                direction,
                hidden,
                pageSize
        );
        DirectoryListing listing = list(path, resolved);
        return result(
                FileBrowserResult.Mode.BROWSE,
                listing,
                listing.directories(),
                listing.files(),
                page,
                resolved,
                "",
                false
        );
    }

    private DirectoryListing list(String path, FileBrowserPreferences.Values resolved) throws IOException {
        return storageService.list(
                StorageScope.VAULT,
                path,
                resolved.sort(),
                resolved.direction(),
                resolved.showHidden()
        );
    }

    private FileBrowserResult result(
            FileBrowserResult.Mode mode,
            DirectoryListing context,
            List<FileItem> directories,
            List<FileItem> entries,
            Integer requestedPage,
            FileBrowserPreferences.Values preferences,
            String query,
            boolean searchPerformed
    ) throws IOException {
        return new FileBrowserResult(
                mode,
                context,
                List.copyOf(directories),
                page(entries, requestedPage, preferences.pageSize()),
                Set.copyOf(favoriteService.favoritePaths()),
                preferences,
                query,
                searchPerformed
        );
    }

    private FilePage page(List<FileItem> entries, Integer requestedPage, int pageSize) {
        int totalItems = entries.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int page = requestedPage == null ? 1 : Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = totalItems == 0 ? 0 : (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<FileItem> items = totalItems == 0 ? List.of() : entries.subList(startIndex, endIndex);
        int startItem = totalItems == 0 ? 0 : startIndex + 1;
        return new FilePage(items, page, pageSize, totalItems, totalPages, startItem, endIndex);
    }
}
