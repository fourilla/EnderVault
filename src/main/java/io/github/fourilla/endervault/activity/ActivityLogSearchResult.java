package io.github.fourilla.endervault.activity;

import java.util.List;

public record ActivityLogSearchResult(
        List<ActivityLogEntry> entries,
        List<String> typeOptions,
        int totalCount,
        int matchedCount,
        int page,
        int size,
        int totalPages
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

    public int firstIndex() {
        return matchedCount == 0 ? 0 : ((page - 1) * size) + 1;
    }

    public int lastIndex() {
        return Math.min(page * size, matchedCount);
    }
}
