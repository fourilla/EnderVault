package io.github.fourilla.endervault.web.file.recent;

import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.web.file.RecentPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RecentBrowserQueryService {

    private final RecentService recentService;
    private final FavoriteService favoriteService;
    private final RecentBrowserPreferences preferences;

    public RecentBrowserQueryService(
            RecentService recentService,
            FavoriteService favoriteService,
            RecentBrowserPreferences preferences
    ) {
        this.recentService = recentService;
        this.favoriteService = favoriteService;
        this.preferences = preferences;
    }

    public RecentBrowserResult query(
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
        RecentBrowserPreferences.Values resolved = preferences.resolve(
                request,
                response,
                view,
                sort,
                direction,
                hidden,
                pageSize
        );
        String normalizedQuery = query == null ? "" : query.trim();
        List<RecentListItem> items = recentService.list(
                normalizedQuery,
                resolved.sort(),
                resolved.direction(),
                resolved.showHidden()
        );
        List<RecentListItem> directories = items.stream().filter(RecentListItem::directory).toList();
        List<RecentListItem> files = items.stream().filter(item -> !item.directory()).toList();
        return new RecentBrowserResult(
                directories,
                page(files, page, resolved.pageSize()),
                Set.copyOf(favoriteService.favoritePaths()),
                resolved,
                normalizedQuery,
                !normalizedQuery.isBlank(),
                items.size()
        );
    }

    private RecentPage page(List<RecentListItem> files, Integer requestedPage, int pageSize) {
        int totalItems = files.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int page = requestedPage == null ? 1 : Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = totalItems == 0 ? 0 : (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<RecentListItem> items = totalItems == 0 ? List.of() : files.subList(startIndex, endIndex);
        int startItem = totalItems == 0 ? 0 : startIndex + 1;
        return new RecentPage(items, page, pageSize, totalItems, totalPages, startItem, endIndex);
    }
}
