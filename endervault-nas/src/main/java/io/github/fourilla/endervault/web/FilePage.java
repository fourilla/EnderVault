package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.FileItem;
import java.util.List;

public record FilePage(
        List<FileItem> items,
        int page,
        int size,
        int totalItems,
        int totalPages,
        int startItem,
        int endItem
) {
    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }

    public int previousPage() {
        return Math.max(1, page - 1);
    }

    public int nextPage() {
        return Math.min(totalPages, page + 1);
    }
}
