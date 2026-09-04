package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.storage.Breadcrumb;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.file.FilePage;
import io.github.fourilla.endervault.web.file.browser.FileBrowserPreferences;
import io.github.fourilla.endervault.web.file.browser.FileBrowserResult;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import java.util.List;
import java.util.Set;

public record FileBrowserPayload(
        String mode,
        String path,
        String parentPath,
        List<BreadcrumbPayload> breadcrumbs,
        List<FileBrowserEntryPayload> directories,
        List<FileBrowserEntryPayload> entries,
        PagePayload page,
        PreferencesPayload preferences,
        SearchPayload search
) {

    static FileBrowserPayload from(FileBrowserResult result, FilePreviewSupport filePreviewSupport) {
        Set<String> favorites = result.favoritePaths();
        return new FileBrowserPayload(
                result.mode().name().toLowerCase(),
                result.context().path(),
                result.context().parentPath(),
                result.context().breadcrumbs().stream().map(BreadcrumbPayload::from).toList(),
                entries(result.directories(), favorites, filePreviewSupport),
                entries(result.entries().items(), favorites, filePreviewSupport),
                PagePayload.from(result.entries()),
                PreferencesPayload.from(result.preferences()),
                new SearchPayload(result.query(), result.searchPerformed())
        );
    }

    private static List<FileBrowserEntryPayload> entries(
            List<FileItem> items,
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

    public record BreadcrumbPayload(String label, String path) {
        static BreadcrumbPayload from(Breadcrumb breadcrumb) {
            return new BreadcrumbPayload(breadcrumb.label(), breadcrumb.path());
        }
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
        static PagePayload from(FilePage page) {
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
        static PreferencesPayload from(FileBrowserPreferences.Values preferences) {
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
