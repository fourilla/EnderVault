package io.github.fourilla.endervault.bookmark;

public record BookmarkBreadcrumb(
        String id,
        String label
) {
    public boolean root() {
        return id == null || id.isBlank();
    }
}
