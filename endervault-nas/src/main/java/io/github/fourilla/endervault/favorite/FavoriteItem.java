package io.github.fourilla.endervault.favorite;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public record FavoriteItem(
        String path,
        FavoriteTargetType type,
        Instant createdAt,
        String title
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean directory() {
        return type == FavoriteTargetType.DIRECTORY;
    }

    public boolean bookmark() {
        return bookmarkLink() || bookmarkDirectory();
    }

    public boolean bookmarkLink() {
        return type == FavoriteTargetType.BOOKMARK_LINK;
    }

    public boolean bookmarkDirectory() {
        return type == FavoriteTargetType.BOOKMARK_DIRECTORY;
    }

    public String bookmarkId() {
        return bookmark() && path != null && path.startsWith("bookmark:")
                ? path.substring("bookmark:".length())
                : "";
    }

    public String name() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (bookmark()) {
            return "Bookmark";
        }
        if (path == null || path.isBlank()) {
            return "Root";
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    public String typeLabel() {
        return switch (type) {
            case DIRECTORY -> "Directory";
            case BOOKMARK_LINK -> "Bookmark Link";
            case BOOKMARK_DIRECTORY -> "Bookmark Directory";
            default -> "File";
        };
    }

    public String iconClass() {
        return switch (type) {
            case DIRECTORY -> "fas fa-folder";
            case BOOKMARK_LINK -> "fas fa-bookmark";
            case BOOKMARK_DIRECTORY -> "fas fa-folder-tree";
            default -> "fas fa-file";
        };
    }

    public String openUrl() {
        return openUrl("open");
    }

    public String openUrl(String bookmarkLinkClickAction) {
        if (bookmarkLink() && "detail".equalsIgnoreCase(bookmarkLinkClickAction == null ? "" : bookmarkLinkClickAction.trim())) {
            return detailUrl();
        }
        if (bookmarkDirectory()) {
            return "/files/bookmarks?directory=" + encode(bookmarkId());
        }
        if (bookmarkLink()) {
            return "/files/bookmarks/open?id=" + encode(bookmarkId());
        }
        if (directory()) {
            return "/files?path=" + encode(path);
        }
        return "/files/detail?path=" + encode(path);
    }

    public String directOpenUrl() {
        if (bookmarkLink()) {
            return "/files/bookmarks/open?id=" + encode(bookmarkId());
        }
        return openUrl("open");
    }

    public String detailUrl() {
        if (bookmark()) {
            return "/files/bookmarks/detail?id=" + encode(bookmarkId());
        }
        if (directory()) {
            return "/files?path=" + encode(path);
        }
        return "/files/detail?path=" + encode(path);
    }

    public boolean opensInNewTab(String bookmarkLinkClickAction) {
        return bookmarkLink() && !"detail".equalsIgnoreCase(bookmarkLinkClickAction == null ? "" : bookmarkLinkClickAction.trim());
    }

    public String targetLabel() {
        return bookmark() ? bookmarkId() : path;
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public FavoriteItem withPath(String newPath) {
        return new FavoriteItem(newPath, type, createdAt, title);
    }

    public FavoriteItem withBookmarkTitle(String newTitle, FavoriteTargetType newType) {
        return new FavoriteItem(path, newType, createdAt, newTitle);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
