package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class BookmarkTree {

    private static final String ROOT_TITLE = "Bookmarks";
    private static final String RECOVERED_DIRECTORY_TITLE = "Recovered Bookmarks";

    String normalizeParentId(String parentId, List<BookmarkItem> bookmarks) {
        String normalizedParentId = normalizeId(parentId);
        if (normalizedParentId == null) {
            return null;
        }
        BookmarkItem parent = require(normalizedParentId, bookmarks);
        if (!parent.directory()) {
            throw new StorageAccessException("Bookmark parent must be a directory.");
        }
        return normalizedParentId;
    }

    String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    boolean sameParent(String candidateParentId, String parentId) {
        String normalizedCandidate = normalizeId(candidateParentId);
        return parentId == null ? normalizedCandidate == null : parentId.equals(normalizedCandidate);
    }

    BookmarkItem currentDirectory(String parentId, List<BookmarkItem> bookmarks) {
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        if (normalizedParentId == null) {
            return null;
        }
        BookmarkItem directory = require(normalizedParentId, bookmarks);
        if (!directory.directory()) {
            throw new StorageAccessException("Bookmark directory not found.");
        }
        return directory;
    }

    List<BookmarkBreadcrumb> breadcrumbs(String parentId, List<BookmarkItem> bookmarks) {
        String normalizedParentId = normalizeParentId(parentId, bookmarks);
        List<BookmarkBreadcrumb> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(new BookmarkBreadcrumb(null, ROOT_TITLE));
        if (normalizedParentId == null) {
            return List.copyOf(breadcrumbs);
        }

        List<BookmarkBreadcrumb> ancestors = new ArrayList<>();
        String cursor = normalizedParentId;
        Set<String> seen = new HashSet<>();
        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw new StorageAccessException("Bookmark directory tree is invalid.");
            }
            BookmarkItem directory = require(cursor, bookmarks);
            if (!directory.directory()) {
                throw new StorageAccessException("Bookmark directory not found.");
            }
            ancestors.add(new BookmarkBreadcrumb(directory.id(), directory.title()));
            cursor = normalizeId(directory.parentId());
        }
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            breadcrumbs.add(ancestors.get(i));
        }
        return List.copyOf(breadcrumbs);
    }

    int indexOf(List<BookmarkItem> bookmarks, String id) {
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new StorageAccessException("Bookmark item not found.");
    }

    BookmarkItem require(String id, List<BookmarkItem> bookmarks) {
        return bookmarks.stream()
                .filter(bookmark -> bookmark.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new StorageAccessException("Bookmark item not found."));
    }

    BookmarkItem ensureRecoveredDirectory(List<BookmarkItem> bookmarks) {
        String titleKey = RECOVERED_DIRECTORY_TITLE.toLowerCase(Locale.ROOT);
        for (BookmarkItem bookmark : bookmarks) {
            if (bookmark.directory()
                    && normalizeId(bookmark.parentId()) == null
                    && titleKey.equals(bookmark.title() == null ? "" : bookmark.title().toLowerCase(Locale.ROOT))) {
                return bookmark;
            }
        }

        Instant now = Instant.now();
        BookmarkItem recoveredDirectory = new BookmarkItem(
                UUID.randomUUID().toString(),
                BookmarkItemType.DIRECTORY,
                null,
                RECOVERED_DIRECTORY_TITLE,
                null,
                null,
                BookmarkTitleSource.MANUAL,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
        bookmarks.add(recoveredDirectory);
        return recoveredDirectory;
    }

    Set<String> descendantDirectoryIds(List<BookmarkItem> bookmarks, String parentId) {
        Set<String> directoryIds = new HashSet<>();
        directoryIds.add(parentId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BookmarkItem bookmark : bookmarks) {
                if (bookmark.directory()
                        && !directoryIds.contains(bookmark.id())
                        && directoryIds.contains(normalizeId(bookmark.parentId()))) {
                    directoryIds.add(bookmark.id());
                    changed = true;
                }
            }
        }
        return directoryIds;
    }

    Set<String> descendantsIncludingSelf(List<BookmarkItem> bookmarks, String id) {
        Set<String> ids = new HashSet<>();
        ids.add(id);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BookmarkItem bookmark : bookmarks) {
                if (!ids.contains(bookmark.id()) && ids.contains(normalizeId(bookmark.parentId()))) {
                    ids.add(bookmark.id());
                    changed = true;
                }
            }
        }
        return ids;
    }

    Comparator<BookmarkItem> comparator() {
        return Comparator.comparing(BookmarkItem::directory).reversed()
                .thenComparing(bookmark -> bookmark.title().toLowerCase(Locale.ROOT))
                .thenComparing(BookmarkItem::id);
    }
}
