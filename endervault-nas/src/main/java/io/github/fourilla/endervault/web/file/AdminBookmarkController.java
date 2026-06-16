package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminBookmarkController {

    private final BookmarkService bookmarkService;
    private final ActivityLogService activityLogService;

    public AdminBookmarkController(BookmarkService bookmarkService, ActivityLogService activityLogService) {
        this.bookmarkService = bookmarkService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/bookmarks")
    public String bookmarks(
            @RequestParam(value = "folder", required = false) String folderId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "edit", required = false) String editId,
            Model model
    ) throws IOException {
        String normalizedQuery = normalizeQuery(query);
        List<BookmarkItem> bookmarkItems = bookmarkService.list(folderId, normalizedQuery);
        model.addAttribute("bookmarkItems", bookmarkItems);
        model.addAttribute("bookmarkFolders", bookmarkItems.stream().filter(BookmarkItem::folder).toList());
        model.addAttribute("bookmarkLinks", bookmarkItems.stream().filter(BookmarkItem::link).toList());
        model.addAttribute("bookmarkBreadcrumbs", bookmarkService.breadcrumbs(folderId));
        model.addAttribute("currentBookmarkFolder", bookmarkService.currentFolder(folderId));
        model.addAttribute("currentBookmarkFolderId", normalizeId(folderId));
        model.addAttribute("editingBookmark", bookmarkService.find(editId));
        model.addAttribute("query", normalizedQuery);
        model.addAttribute("searchPerformed", !normalizedQuery.isBlank());
        return "bookmarks";
    }

    @PostMapping("/files/bookmarks/folders")
    public String createFolder(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem folder = bookmarkService.createFolder(parentId, title);
            activityLogService.record(
                    "BOOKMARK_DIRECTORY_CREATE",
                    request,
                    null,
                    null,
                    "Created bookmark directory " + folder.title(),
                    Map.of("bookmarkId", folder.id())
            );
            FlashNotifications.success(redirectAttributes, "Bookmark directory created.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, null);
    }

    @PostMapping("/files/bookmarks/links")
    public String createLink(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam("url") String url,
            @RequestParam(value = "note", required = false) String note,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem link = bookmarkService.createLink(parentId, title, url, note);
            activityLogService.record(
                    "BOOKMARK_LINK_CREATE",
                    request,
                    link.url(),
                    null,
                    "Created bookmark link " + link.title(),
                    Map.of("bookmarkId", link.id())
            );
            FlashNotifications.success(redirectAttributes, "Bookmark link created.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, null);
    }

    @PostMapping("/files/bookmarks/bulk")
    public String bulkCreateLinks(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("bulkText") String bulkText,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            List<BookmarkItem> links = bookmarkService.createLinks(parentId, bulkText);
            activityLogService.record(
                    "BOOKMARK_BULK_CREATE",
                    request,
                    null,
                    null,
                    "Created " + links.size() + " bookmark links",
                    Map.of("count", String.valueOf(links.size()))
            );
            FlashNotifications.success(redirectAttributes, "Bookmark links created: " + links.size());
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, null);
    }

    @PostMapping("/files/bookmarks/folders/update")
    public String updateFolder(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem folder = bookmarkService.updateFolder(id, title);
            activityLogService.record(
                    "BOOKMARK_UPDATE",
                    request,
                    null,
                    null,
                    "Updated bookmark directory " + folder.title(),
                    Map.of("bookmarkId", folder.id(), "type", "directory")
            );
            FlashNotifications.success(redirectAttributes, "Bookmark directory updated.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, query);
    }

    @PostMapping("/files/bookmarks/links/update")
    public String updateLink(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam("url") String url,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem link = bookmarkService.updateLink(id, title, url, note);
            activityLogService.record(
                    "BOOKMARK_UPDATE",
                    request,
                    link.url(),
                    null,
                    "Updated bookmark link " + link.title(),
                    Map.of("bookmarkId", link.id(), "type", "link")
            );
            FlashNotifications.success(redirectAttributes, "Bookmark link updated.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, query);
    }

    @PostMapping("/files/bookmarks/delete")
    public String delete(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem removed = bookmarkService.delete(id);
            activityLogService.record(
                    "BOOKMARK_DELETE",
                    request,
                    removed.url(),
                    null,
                    "Deleted bookmark " + removed.title(),
                    Map.of("bookmarkId", removed.id(), "type", removed.typeLabel().toLowerCase())
            );
            FlashNotifications.success(redirectAttributes, "Bookmark deleted.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, query);
    }

    @PostMapping("/files/bookmarks/delete-selected")
    public String deleteSelected(
            @RequestParam(value = "bookmarkIds", required = false) List<String> bookmarkIds,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> selectedIds = safeIds(bookmarkIds);
        if (selectedIds.isEmpty()) {
            FlashNotifications.warning(redirectAttributes, "Select at least one bookmark item.");
            return redirectToBookmarks(parentId, query);
        }

        int deletedCount = bookmarkService.deleteAll(selectedIds);
        if (deletedCount > 0) {
            activityLogService.record(
                    "BOOKMARK_DELETE_SELECTED",
                    request,
                    null,
                    null,
                    "Deleted selected bookmark items",
                    Map.of("count", String.valueOf(deletedCount))
            );
            FlashNotifications.success(redirectAttributes, "Selected bookmark items deleted: " + deletedCount);
        } else {
            FlashNotifications.warning(redirectAttributes, "Selected bookmark items no longer exist.");
        }
        return redirectToBookmarks(parentId, query);
    }

    @GetMapping("/files/bookmarks/open")
    public String open(
            @RequestParam("id") String id,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem link = bookmarkService.recordOpen(id);
        activityLogService.record(
                "BOOKMARK_OPEN",
                request,
                link.url(),
                null,
                "Opened bookmark " + link.title(),
                Map.of("bookmarkId", link.id())
        );
        return "redirect:" + link.url();
    }

    private String redirectToBookmarks(String folderId, String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/bookmarks");
        String normalizedFolderId = normalizeId(folderId);
        if (normalizedFolderId != null) {
            builder.queryParam("folder", normalizedFolderId);
        }
        String normalizedQuery = normalizeQuery(query);
        if (!normalizedQuery.isBlank()) {
            builder.queryParam("q", normalizedQuery);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private List<String> safeIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
