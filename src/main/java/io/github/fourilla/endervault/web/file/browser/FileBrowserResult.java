package io.github.fourilla.endervault.web.file.browser;

import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.file.FilePage;
import java.util.List;
import java.util.Set;

public record FileBrowserResult(
        Mode mode,
        DirectoryListing context,
        List<FileItem> directories,
        FilePage entries,
        Set<String> favoritePaths,
        FileBrowserPreferences.Values preferences,
        String query,
        boolean searchPerformed
) {
    public enum Mode {
        BROWSE,
        SEARCH
    }
}
