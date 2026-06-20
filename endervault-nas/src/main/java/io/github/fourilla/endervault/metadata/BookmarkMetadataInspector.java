package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BookmarkMetadataInspector implements MetadataInspector {

    private final BookmarkService bookmarkService;

    public BookmarkMetadataInspector(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.BOOKMARKS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        List<BookmarkItem> items = bookmarkService.storedItems();
        Map<String, BookmarkItem> byId = new HashMap<>();
        Set<String> seenIds = new HashSet<>();
        List<MetadataIssue> issues = new ArrayList<>();

        for (BookmarkItem item : items) {
            if (context != null) {
                context.checkCanceled();
            }
            if (item.id() == null || item.id().isBlank()) {
                issues.add(unrepairable(item, "Bookmark item has no id", "Manual cleanup is required."));
                continue;
            }
            if (!seenIds.add(item.id())) {
                issues.add(unrepairable(item, "Bookmark item id is duplicated", "Manual cleanup is required."));
                continue;
            }
            byId.put(item.id(), item);
        }

        for (BookmarkItem item : items) {
            if (context != null) {
                context.checkCanceled();
            }
            if (item.id() == null || item.id().isBlank()) {
                continue;
            }
            String parentId = normalizedId(item.parentId());
            if (parentId == null) {
                continue;
            }
            BookmarkItem parent = byId.get(parentId);
            if (parent == null) {
                issues.add(moveToRecoveredIssue(item, "Bookmark parent is missing", "Move this item to Recovered Bookmarks."));
                continue;
            }
            if (!parent.directory()) {
                issues.add(moveToRecoveredIssue(item, "Bookmark parent is not a directory", "Move this item to Recovered Bookmarks."));
                continue;
            }
            if (hasAncestorCycle(item, byId)) {
                issues.add(moveToRecoveredIssue(item, "Bookmark directory tree has a cycle", "Move this item to Recovered Bookmarks."));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.MOVE_BOOKMARK_TO_ROOT
                && action != MetadataIssueAction.MOVE_BOOKMARK_TO_RECOVERED_DIRECTORY) {
            throw new IllegalArgumentException("Unsupported bookmark repair action.");
        }
        BookmarkItem item = bookmarkService.moveToRecoveredDirectory(subject);
        return "Moved bookmark item to Recovered Bookmarks: " + item.title();
    }

    private MetadataIssue moveToRecoveredIssue(BookmarkItem item, String title, String recommendation) {
        return new MetadataIssue(
                area(),
                MetadataIssueSeverity.WARNING,
                MetadataIssueAction.MOVE_BOOKMARK_TO_RECOVERED_DIRECTORY,
                item.id(),
                title,
                item.title() + " (" + item.id() + ")",
                recommendation
        );
    }

    private MetadataIssue unrepairable(BookmarkItem item, String title, String recommendation) {
        return new MetadataIssue(
                area(),
                MetadataIssueSeverity.DANGER,
                MetadataIssueAction.NONE,
                item.id() == null ? "" : item.id(),
                title,
                item.title() == null ? "(missing title)" : item.title(),
                recommendation
        );
    }

    private boolean hasAncestorCycle(BookmarkItem item, Map<String, BookmarkItem> byId) {
        Set<String> seen = new HashSet<>();
        String cursor = normalizedId(item.parentId());
        while (cursor != null) {
            if (!seen.add(cursor)) {
                return true;
            }
            BookmarkItem parent = byId.get(cursor);
            if (parent == null) {
                return false;
            }
            cursor = normalizedId(parent.parentId());
        }
        return false;
    }

    private String normalizedId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }
}
