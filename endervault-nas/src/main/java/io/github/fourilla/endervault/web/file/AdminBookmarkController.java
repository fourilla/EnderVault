package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.bookmark.BookmarkService.BookmarkFavicon;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
            @RequestParam(value = "directory", required = false) String directoryId,
            @RequestParam(value = "q", required = false) String query,
            Model model
    ) throws IOException {
        String currentDirectoryId = normalizeId(directoryId);
        String normalizedQuery = normalizeQuery(query);
        List<BookmarkItem> bookmarkItems = bookmarkService.list(currentDirectoryId, normalizedQuery);
        model.addAttribute("bookmarkItems", bookmarkItems);
        model.addAttribute("bookmarkDirectories", bookmarkItems.stream().filter(BookmarkItem::directory).toList());
        model.addAttribute("bookmarkLinks", bookmarkItems.stream().filter(BookmarkItem::link).toList());
        model.addAttribute("bookmarkBreadcrumbs", bookmarkService.breadcrumbs(currentDirectoryId));
        model.addAttribute("currentBookmarkDirectory", bookmarkService.currentDirectory(currentDirectoryId));
        model.addAttribute("currentBookmarkDirectoryId", normalizeId(currentDirectoryId));
        model.addAttribute("bookmarkMetadataFetchEnabled", bookmarkService.metadataFetchEnabled());
        model.addAttribute("query", normalizedQuery);
        model.addAttribute("searchPerformed", !normalizedQuery.isBlank());
        return "bookmarks";
    }

    @GetMapping("/files/bookmarks/detail")
    public String detail(
            @RequestParam("id") String id,
            Model model,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        BookmarkItem bookmark = bookmarkService.find(id);
        if (bookmark == null) {
            FlashNotifications.error(redirectAttributes, "Bookmark item not found.");
            return "redirect:/files/bookmarks";
        }

        String parentId = normalizeId(bookmark.parentId());
        model.addAttribute("bookmark", bookmark);
        model.addAttribute("bookmarkBreadcrumbs", bookmarkService.breadcrumbs(parentId));
        model.addAttribute("currentBookmarkDirectory", bookmarkService.currentDirectory(parentId));
        model.addAttribute("currentBookmarkDirectoryId", parentId);
        model.addAttribute("bookmarkMetadataFetchEnabled", bookmarkService.metadataFetchEnabled());
        model.addAttribute("metadataFetchAttempted", metadataFetchAttempted(bookmark));
        model.addAttribute("metadataFetchStatus", metadataFetchStatus(bookmark));
        return "bookmark-detail";
    }

    @PostMapping("/files/bookmarks/directories")
    public String createDirectory(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem directory = bookmarkService.createDirectory(parentId, title);
            activityLogService.record(
                    "BOOKMARK_DIRECTORY_CREATE",
                    request,
                    null,
                    null,
                    "Created bookmark directory " + directory.title(),
                    Map.of("bookmarkId", directory.id())
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
                    bookmarkLogMetadata(link, "link")
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
                    bulkBookmarkLogMetadata(links)
            );
            FlashNotifications.success(redirectAttributes, "Bookmark links created: " + links.size());
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        return redirectToBookmarks(parentId, null);
    }

    @PostMapping("/files/bookmarks/directories/update")
    public String updateDirectory(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem directory = bookmarkService.updateDirectory(id, title);
            activityLogService.record(
                    "BOOKMARK_UPDATE",
                    request,
                    null,
                    null,
                    "Updated bookmark directory " + directory.title(),
                    Map.of("bookmarkId", directory.id(), "type", "directory")
            );
            FlashNotifications.success(redirectAttributes, "Bookmark directory updated.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        if (returnToDetail) {
            return redirectToBookmarkDetail(id);
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
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
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
                    bookmarkLogMetadata(link, "link")
            );
            FlashNotifications.success(redirectAttributes, "Bookmark link updated.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        if (returnToDetail) {
            return redirectToBookmarkDetail(id);
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

    @PostMapping("/files/bookmarks/metadata")
    public String refreshMetadata(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            BookmarkItem link = bookmarkService.refreshMetadata(id);
            activityLogService.record(
                    "BOOKMARK_METADATA_FETCH",
                    request,
                    link.url(),
                    null,
                    "Fetched bookmark metadata for " + link.title(),
                    bookmarkLogMetadata(link, "link")
            );
            FlashNotifications.success(redirectAttributes, "Bookmark metadata updated.");
        } catch (StorageAccessException ex) {
            FlashNotifications.error(redirectAttributes, ex.getMessage());
        }
        if (returnToDetail) {
            return redirectToBookmarkDetail(id);
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

    @GetMapping("/files/bookmarks/favicon")
    public ResponseEntity<Resource> favicon(@RequestParam("id") String id) throws IOException {
        BookmarkFavicon favicon = bookmarkService.favicon(id);
        MediaType mediaType = parseMediaType(favicon.contentType());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .body(new PathResource(favicon.path()));
    }

    private String redirectToBookmarks(String directoryId, String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/bookmarks");
        String normalizedDirectoryId = normalizeId(directoryId);
        if (normalizedDirectoryId != null) {
            builder.queryParam("directory", normalizedDirectoryId);
        }
        String normalizedQuery = normalizeQuery(query);
        if (!normalizedQuery.isBlank()) {
            builder.queryParam("q", normalizedQuery);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectToBookmarkDetail(String id) {
        return "redirect:" + UriComponentsBuilder.fromPath("/files/bookmarks/detail")
                .queryParam("id", id)
                .build()
                .encode()
                .toUriString();
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

    private Map<String, String> bookmarkLogMetadata(BookmarkItem bookmark, String type) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("bookmarkId", bookmark.id());
        metadata.put("type", type);
        metadata.put("titleSource", String.valueOf(bookmark.effectiveTitleSource()));
        metadata.put("metadataFetchAttempted", String.valueOf(metadataFetchAttempted(bookmark)));
        metadata.put("metadataFetchStatus", metadataFetchStatus(bookmark));
        metadata.put("faviconAvailable", String.valueOf(bookmark.faviconAvailable()));
        return metadata;
    }

    private Map<String, String> bulkBookmarkLogMetadata(List<BookmarkItem> bookmarks) {
        List<BookmarkItem> safeBookmarks = bookmarks == null ? List.of() : bookmarks;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("count", String.valueOf(safeBookmarks.size()));
        metadata.put("metadataFetchAttemptedCount", String.valueOf(safeBookmarks.stream()
                .filter(this::metadataFetchAttempted)
                .count()));
        metadata.put("metadataFetchOkCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "OK".equals(metadataFetchStatus(bookmark)))
                .count()));
        metadata.put("faviconAvailableCount", String.valueOf(safeBookmarks.stream()
                .filter(BookmarkItem::faviconAvailable)
                .count()));
        metadata.put("manualTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "MANUAL".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        metadata.put("urlDerivedTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "URL_DERIVED".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        metadata.put("remoteTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "REMOTE_TITLE".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        return metadata;
    }

    private boolean metadataFetchAttempted(BookmarkItem bookmark) {
        return bookmark != null
                && (bookmark.metadataFetchedAt() != null
                || (bookmark.metadataFetchStatus() != null && !bookmark.metadataFetchStatus().isBlank()));
    }

    private String metadataFetchStatus(BookmarkItem bookmark) {
        if (!metadataFetchAttempted(bookmark)) {
            return "SKIPPED";
        }
        String status = bookmark.metadataFetchStatus();
        return status == null || status.isBlank() ? "UNKNOWN" : status;
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
