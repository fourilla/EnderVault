package io.github.fourilla.endervault.storage;

import java.util.List;

public record DirectoryListing(
        String path,
        String parentPath,
        List<Breadcrumb> breadcrumbs,
        List<FileItem> directories,
        List<FileItem> files
) {
    public boolean hasParent() {
        return parentPath != null;
    }
}

