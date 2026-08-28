package io.github.fourilla.endervault.web.api.v1.recent;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.web.api.v1.fs.FileBrowserEntryPayload;
import io.github.fourilla.endervault.web.file.RecentPage;
import io.github.fourilla.endervault.web.file.recent.RecentBrowserPreferences;
import io.github.fourilla.endervault.web.file.recent.RecentBrowserResult;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import java.util.List;
import java.util.Set;

public record RecentBrowserPayload(
        List<FileBrowserEntryPayload> directories,
        List<FileBrowserEntryPayload> entries,
        PagePayload page,
        PreferencesPayload preferences,
        SearchPayload search,
        int totalItems
) {

    static RecentBrowserPayload from(RecentBrowserResult result, FilePreviewSupport filePreviewSupport) {
        return new RecentBrowserPayload(
                entries(result.directories(), result.favoritePaths(), filePreviewSupport),
                entries(result.files().items(), result.favoritePaths(), filePreviewSupport),
                PagePayload.from(result.files()),
                PreferencesPayload.from(result.preferences()),
                new SearchPayload(result.query(), result.searchPerformed()),
                result.totalItems()
        );
    }

    private static List<FileBrowserEntryPayload> entries(
            List<RecentListItem> items,
            Set<String> favorites,
            FilePreviewSupport filePreviewSupport
    ) {
        return items.stream()
                .map(item -> FileBrowserEntryPayload.from(
                        item,
                        favorites.contains(item.path()),
                        filePreviewSupport
                ))
                .toList();
    }

    public record PagePayload(
            int number,
            int size,
            int totalItems,
            int totalPages,
            int startItem,
            int endItem,
            boolean hasPrevious,
            boolean hasNext
    ) {
        static PagePayload from(RecentPage page) {
            return new PagePayload(
                    page.page(),
                    page.size(),
                    page.totalItems(),
                    page.totalPages(),
                    page.startItem(),
                    page.endItem(),
                    page.hasPrevious(),
                    page.hasNext()
            );
        }
    }

    public record PreferencesPayload(
            String view,
            String sort,
            String direction,
            String hidden,
            int pageSize,
            List<Integer> pageSizeOptions
    ) {
        static PreferencesPayload from(RecentBrowserPreferences.Values preferences) {
            return new PreferencesPayload(
                    preferences.view(),
                    preferences.sort().parameter(),
                    preferences.direction().parameter(),
                    preferences.hiddenMode(),
                    preferences.pageSize(),
                    preferences.pageSizeOptions()
            );
        }
    }

    public record SearchPayload(String query, boolean performed) {
    }
}
