package io.github.fourilla.endervault.web.api.v1.recent;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.web.file.recent.RecentBrowserQueryService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/recent")
public class RecentApiController {

    private final RecentService recentService;
    private final ActivityLogService activityLogService;
    private final RecentBrowserQueryService queryService;
    private final FilePreviewSupport filePreviewSupport;

    public RecentApiController(
            RecentService recentService,
            ActivityLogService activityLogService,
            RecentBrowserQueryService queryService,
            FilePreviewSupport filePreviewSupport
    ) {
        this.recentService = recentService;
        this.activityLogService = activityLogService;
        this.queryService = queryService;
        this.filePreviewSupport = filePreviewSupport;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public RecentBrowserPayload list(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        return RecentBrowserPayload.from(queryService.query(
                query,
                view,
                sort,
                direction,
                hidden,
                page,
                size,
                request,
                response
        ), filePreviewSupport);
    }

    @PostMapping("/remove")
    public ResponseEntity<ActionResponse> removeSelected(
            @RequestParam(value = "paths", required = false) List<String> paths,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page
    ) throws IOException {
        List<String> selectedPaths = safePaths(paths);
        if (selectedPaths.isEmpty()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one recent item."));
        }

        recentService.removeAll(selectedPaths);
        return ResponseEntity.ok(ActionResponse.redirect(
                FlashNotification.success("Selected recent items removed."),
                recentUrl(query, page)
        ));
    }

    @PostMapping("/clear")
    public ActionResponse clear(HttpServletRequest request) throws IOException {
        recentService.clear();
        activityLogService.record("RECENT_CLEAR", request, null, null, "Cleared recent history");
        return ActionResponse.redirect(
                FlashNotification.success("Recent history cleared."),
                "/files/recent"
        );
    }

    private List<String> safePaths(List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        return paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    private String recentUrl(String query, Integer page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/recent");
        if (query != null && !query.isBlank()) {
            builder.queryParam("q", query);
        }
        int pageNumber = page == null ? 1 : Math.max(1, page);
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        return builder.build().encode().toUriString();
    }
}
