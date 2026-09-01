package io.github.fourilla.endervault.web.api.v1.bookmark;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkLogMetadata;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.BookmarkBulkTaskService;
import io.github.fourilla.endervault.web.bookmark.BookmarkRoutes;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.BookmarkLinkClickAction;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskActionResponse;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookmarks")
public class BookmarkApiController {

    private final BookmarkService bookmarkService;
    private final BookmarkBulkTaskService bookmarkBulkTaskService;
    private final ActivityLogService activityLogService;
    private final FavoriteService favoriteService;
    private final NasProperties nasProperties;
    private final BookmarkDetailQueryService bookmarkDetailQueryService;

    public BookmarkApiController(
            BookmarkService bookmarkService,
            BookmarkBulkTaskService bookmarkBulkTaskService,
            ActivityLogService activityLogService,
            FavoriteService favoriteService,
            NasProperties nasProperties,
            BookmarkDetailQueryService bookmarkDetailQueryService
    ) {
        this.bookmarkService = bookmarkService;
        this.bookmarkBulkTaskService = bookmarkBulkTaskService;
        this.activityLogService = activityLogService;
        this.favoriteService = favoriteService;
        this.nasProperties = nasProperties;
        this.bookmarkDetailQueryService = bookmarkDetailQueryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public BookmarkBrowserPayload list(
            @RequestParam(value = "directory", required = false) String directoryId,
            @RequestParam(value = "q", required = false) String query
    ) throws IOException {
        String currentDirectoryId = BookmarkRoutes.normalizeId(directoryId);
        String normalizedQuery = BookmarkRoutes.normalizeQuery(query);
        bookmarkService.currentDirectory(currentDirectoryId);
        boolean metadataFetchEnabled = bookmarkService.metadataFetchEnabled();
        return BookmarkBrowserPayload.from(
                bookmarkService.list(currentDirectoryId, normalizedQuery),
                bookmarkService.breadcrumbs(currentDirectoryId),
                currentDirectoryId,
                normalizedQuery,
                metadataFetchEnabled,
                BookmarkLinkClickAction.from(nasProperties),
                favoriteService.favoriteBookmarkIds()
        );
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BookmarkDetailPayload detail(@PathVariable("id") String id)
            throws IOException {
        return bookmarkDetailQueryService.load(id);
    }

    @PostMapping("/directories")
    public ActionResponse createDirectory(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem directory = bookmarkService.createDirectory(parentId, title);
        activityLogService.record(
                "BOOKMARK_DIRECTORY_CREATE",
                request,
                null,
                null,
                "Created bookmark directory " + directory.title(),
                Map.of("bookmarkId", directory.id())
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark directory created."),
                BookmarkRoutes.bookmarksUrl(parentId, null)
        );
    }

    @PostMapping("/links")
    public ActionResponse createLink(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam("url") String url,
            @RequestParam(value = "note", required = false) String note,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem link = bookmarkService.createLink(parentId, title, url, note);
        activityLogService.record(
                "BOOKMARK_LINK_CREATE",
                request,
                link.url(),
                null,
                "Created bookmark link " + link.title(),
                BookmarkLogMetadata.single(link, "link")
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark link created."),
                BookmarkRoutes.bookmarksUrl(parentId, null)
        );
    }

    @PostMapping("/bulk")
    public ResponseEntity<TaskActionResponse> bulkCreateLinks(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("bulkText") String bulkText,
            HttpServletRequest request
    ) throws IOException {
        AppTask task = bookmarkBulkTaskService.queueCreateLinks(parentId, bulkText, request);
        return ResponseEntity.accepted().body(TaskActionResponse.ok(
                FlashNotification.info("Bookmark bulk add task queued."),
                TaskPayload.from(task)
        ));
    }

    @PostMapping("/directories/update")
    public ActionResponse updateDirectory(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem directory = bookmarkService.updateDirectory(id, title);
        activityLogService.record(
                "BOOKMARK_UPDATE",
                request,
                null,
                null,
                "Updated bookmark directory " + directory.title(),
                Map.of("bookmarkId", directory.id(), "type", "directory")
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark directory updated."),
                returnUrl(id, parentId, query, returnToDetail)
        );
    }

    @PostMapping("/links/update")
    public ActionResponse updateLink(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("title") String title,
            @RequestParam("url") String url,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem link = bookmarkService.updateLink(id, title, url, note);
        activityLogService.record(
                "BOOKMARK_UPDATE",
                request,
                link.url(),
                null,
                "Updated bookmark link " + link.title(),
                BookmarkLogMetadata.single(link, "link")
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark link updated."),
                returnUrl(id, parentId, query, returnToDetail)
        );
    }

    @PostMapping("/delete")
    public ActionResponse delete(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem removed = bookmarkService.delete(id);
        activityLogService.record(
                "BOOKMARK_DELETE",
                request,
                removed.url(),
                null,
                "Deleted bookmark " + removed.title(),
                Map.of("bookmarkId", removed.id(), "type", removed.typeLabel().toLowerCase())
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark deleted."),
                BookmarkRoutes.bookmarksUrl(parentId, query)
        );
    }

    @PostMapping("/delete-selected")
    public ResponseEntity<ActionResponse> deleteSelected(
            @RequestParam(value = "bookmarkIds", required = false) List<String> bookmarkIds,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            HttpServletRequest request
    ) throws IOException {
        List<String> selectedIds = BookmarkRoutes.safeIds(bookmarkIds);
        if (selectedIds.isEmpty()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one bookmark item."));
        }

        int deletedCount = bookmarkService.deleteAll(selectedIds);
        FlashNotification notification;
        if (deletedCount > 0) {
            activityLogService.record(
                    "BOOKMARK_DELETE_SELECTED",
                    request,
                    null,
                    null,
                    "Deleted selected bookmark items",
                    Map.of("count", String.valueOf(deletedCount))
            );
            notification = FlashNotification.success("Selected bookmark items deleted: " + deletedCount);
        } else {
            notification = FlashNotification.warning("Selected bookmark items no longer exist.");
        }
        return ResponseEntity.ok(ActionResponse.redirect(
                notification,
                BookmarkRoutes.bookmarksUrl(parentId, query)
        ));
    }

    @PostMapping("/metadata")
    public ActionResponse refreshMetadata(
            @RequestParam("id") String id,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "returnToDetail", required = false, defaultValue = "false") boolean returnToDetail,
            HttpServletRequest request
    ) throws IOException {
        BookmarkItem link = bookmarkService.refreshMetadata(id);
        activityLogService.record(
                "BOOKMARK_METADATA_FETCH",
                request,
                link.url(),
                null,
                "Fetched bookmark metadata for " + link.title(),
                BookmarkLogMetadata.single(link, "link")
        );
        return ActionResponse.redirect(
                FlashNotification.success("Bookmark metadata updated."),
                returnUrl(id, parentId, query, returnToDetail)
        );
    }

    @ExceptionHandler(StorageAccessException.class)
    public ResponseEntity<ActionResponse> invalidRequest(StorageAccessException exception) {
        return ResponseEntity.badRequest().body(ActionResponse.error(exception.getMessage()));
    }

    private String returnUrl(String id, String parentId, String query, boolean returnToDetail) {
        return returnToDetail
                ? BookmarkRoutes.bookmarkDetailUrl(id)
                : BookmarkRoutes.bookmarksUrl(parentId, query);
    }
}
