package io.github.fourilla.endervault.web.bookmark;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.bookmark.BookmarkService.BookmarkFavicon;
import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminBookmarkController {

    private final BookmarkService bookmarkService;
    private final ActivityLogService activityLogService;
    private final AdminSpaViewService adminSpaViewService;

    public AdminBookmarkController(
            BookmarkService bookmarkService,
            ActivityLogService activityLogService,
            AdminSpaViewService adminSpaViewService
    ) {
        this.bookmarkService = bookmarkService;
        this.activityLogService = activityLogService;
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/files/bookmarks")
    public String bookmarks(
            Model model
    ) {
        return adminSpaViewService.render(model);
    }

    @GetMapping("/files/bookmarks/detail")
    public String detail(
            @RequestParam("id") String id,
            Model model
    ) {
        return adminSpaViewService.render(model);
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
        MediaType mediaType = BookmarkRoutes.mediaType(favicon.contentType());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .body(new PathResource(favicon.path()));
    }
}
