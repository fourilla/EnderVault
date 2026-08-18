package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.bookmark.BookmarkFaviconCacheFile;
import io.github.fourilla.endervault.bookmark.BookmarkFaviconTemporaryFile;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import java.io.IOException;
import java.time.Instant;
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
    private final TemporaryArtifactRetentionPolicy retentionPolicy;

    public BookmarkMetadataInspector(
            BookmarkService bookmarkService,
            TemporaryArtifactRetentionPolicy retentionPolicy
    ) {
        this.bookmarkService = bookmarkService;
        this.retentionPolicy = retentionPolicy;
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
        Instant now = Instant.now();
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
        for (BookmarkFaviconCacheFile file : bookmarkService.orphanFaviconCacheFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            issues.add(orphanFaviconIssue(file));
        }
        for (BookmarkFaviconTemporaryFile file : bookmarkService.temporaryFaviconCacheFiles()) {
            if (context != null) {
                context.checkCanceled();
            }
            issues.add(temporaryFaviconIssue(file, retentionPolicy.isStale(file.modifiedAt(), now)));
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action == MetadataIssueAction.DELETE_BOOKMARK_FAVICON_CACHE) {
            bookmarkService.deleteFaviconCacheFile(subject);
            return "Deleted bookmark favicon cache: " + subject;
        }
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

    private MetadataIssue orphanFaviconIssue(BookmarkFaviconCacheFile file) {
        String registryState = file.registered() ? "registered cache entry" : "unregistered cache file";
        return new MetadataIssue(
                area(),
                MetadataIssueSeverity.INFO,
                MetadataIssueAction.DELETE_BOOKMARK_FAVICON_CACHE,
                file.fileName(),
                "Bookmark favicon cache is not referenced",
                file.fileName() + " (" + registryState + ", " + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                "Delete this orphan bookmark favicon cache."
        );
    }

    private MetadataIssue temporaryFaviconIssue(BookmarkFaviconTemporaryFile file, boolean stale) {
        boolean repairable = stale && !file.active();
        return new MetadataIssue(
                area(),
                repairable ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                repairable ? MetadataIssueAction.DELETE_BOOKMARK_FAVICON_CACHE : MetadataIssueAction.NONE,
                file.fileName(),
                file.active()
                        ? "Active bookmark favicon temporary file"
                        : stale
                                ? "Stale bookmark favicon temporary file remains"
                                : "Fresh bookmark favicon temporary file exists",
                file.fileName() + " (" + file.sizeLabel() + ", modified " + file.modifiedLabel() + ")",
                file.active()
                        ? "In use by " + file.activeOperation() + ". The inspector will not delete it."
                        : repairable
                                ? "Delete this disposable favicon temporary file."
                                : "Review only. It may belong to a recent metadata fetch."
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
