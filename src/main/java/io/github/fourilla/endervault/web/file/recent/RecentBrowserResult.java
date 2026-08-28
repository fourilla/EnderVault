package io.github.fourilla.endervault.web.file.recent;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.web.file.RecentPage;
import java.util.List;
import java.util.Set;

public record RecentBrowserResult(
        List<RecentListItem> directories,
        RecentPage files,
        Set<String> favoritePaths,
        RecentBrowserPreferences.Values preferences,
        String query,
        boolean searchPerformed,
        int totalItems
) {
}
