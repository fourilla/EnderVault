package io.github.fourilla.endervault.favorite;

public record FavoriteDisplayItem(
        FavoriteItem item,
        boolean hidden
) {
    public String path() {
        return item.path();
    }

    public boolean directory() {
        return item.directory();
    }

    public boolean bookmarkLink() {
        return item.bookmarkLink();
    }

    public String name() {
        return item.name();
    }

    public String typeLabel() {
        return item.typeLabel();
    }

    public String iconClass() {
        return item.iconClass();
    }

    public String openUrl(String bookmarkLinkClickAction) {
        return item.openUrl(bookmarkLinkClickAction);
    }

    public String directOpenUrl() {
        return item.directOpenUrl();
    }

    public String detailUrl() {
        return item.detailUrl();
    }

    public boolean opensInNewTab(String bookmarkLinkClickAction) {
        return item.opensInNewTab(bookmarkLinkClickAction);
    }

    public String targetLabel() {
        return item.targetLabel();
    }

    public String createdLabel() {
        return item.createdLabel();
    }
}
