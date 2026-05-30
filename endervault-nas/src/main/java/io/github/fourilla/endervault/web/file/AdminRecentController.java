package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.recent.RecentSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
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
public class AdminRecentController {

    private static final int FALLBACK_PAGE_SIZE = 200;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;
    private final RecentService recentService;
    private final FavoriteService favoriteService;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;

    public AdminRecentController(
            NasProperties nasProperties,
            RecentService recentService,
            FavoriteService favoriteService,
            StorageService storageService,
            ActivityLogService activityLogService
    ) {
        this.nasProperties = nasProperties;
        this.recentService = recentService;
        this.favoriteService = favoriteService;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/recent")
    public String recent(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) throws IOException {
        String normalizedView = normalizeView(view);
        RecentSort recentSort = normalizeSort(sort);
        SortDirection sortDirection = normalizeDirection(direction, recentSort);
        int pageSize = normalizePageSize(size);
        String normalizedQuery = normalizeQuery(query);

        List<RecentListItem> items = recentService.list(normalizedQuery, recentSort, sortDirection);
        List<RecentListItem> directories = items.stream().filter(RecentListItem::directory).toList();
        List<RecentListItem> files = items.stream().filter(item -> !item.directory()).toList();
        RecentPage filePage = pageFiles(files, page, pageSize);

        model.addAttribute("recentDirectories", directories);
        model.addAttribute("filePage", filePage);
        model.addAttribute("query", normalizedQuery);
        model.addAttribute("searchPerformed", !normalizedQuery.isBlank());
        model.addAttribute("view", normalizedView);
        model.addAttribute("nextView", nextView(normalizedView));
        model.addAttribute("viewToggleLabel", viewToggleLabel(normalizedView));
        model.addAttribute("viewToggleIcon", viewToggleIcon(normalizedView));
        model.addAttribute("sort", recentSort.parameter());
        model.addAttribute("dir", sortDirection.parameter());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("favoritePaths", favoriteService.favoritePaths());
        model.addAttribute("recentTotalItems", items.size());
        return "recent";
    }

    @PostMapping("/files/recent/remove")
    public Object removeSelected(
            @RequestParam(value = "paths", required = false) List<String> paths,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> selectedPaths = safePaths(paths);
        if (selectedPaths.isEmpty()) {
            if (wantsJson(request)) {
                return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one recent item."));
            }
            FlashNotifications.warning(redirectAttributes, "Select at least one recent item.");
            return redirectToRecent(query, view, sort, direction, page, size);
        }

        recentService.removeAll(selectedPaths);
        FlashNotification notification = FlashNotification.success("Selected recent items removed.");
        String redirect = redirectToRecent(query, view, sort, direction, page, size);
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirect)));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirect;
    }

    @PostMapping("/files/recent/clear")
    public Object clear(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        recentService.clear();
        FlashNotification notification = FlashNotification.success("Recent history cleared.");
        activityLogService.record("RECENT_CLEAR", request, null, null, "Cleared recent history");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, "/files/recent"));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return "redirect:/files/recent";
    }

    @GetMapping("/files/recent/download.zip")
    public void downloadSelected(
            @RequestParam(value = "paths", required = false) List<String> paths,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        List<String> selectedPaths = safePaths(paths);
        if (selectedPaths.isEmpty()) {
            response.sendRedirect("/files/recent");
            return;
        }

        if (selectedPaths.size() == 1) {
            RecentListItem item = recentItem(selectedPaths.get(0));
            if (!item.directory()) {
                activityLogService.record("DOWNLOAD", request, item.path(), null, "Downloaded " + item.name());
                response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
                Path file = storageService.resolveVaultFile(item.path());
                response.setContentLengthLong(Files.size(file));
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(item.name()));
                Files.copy(file, response.getOutputStream());
                return;
            }
        }

        activityLogService.record(
                "DOWNLOAD_ZIP",
                request,
                null,
                null,
                "Downloaded ZIP from recent",
                Map.of("count", String.valueOf(selectedPaths.size()))
        );
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, zipContentDisposition(selectedPaths));
        storageService.writeVaultPathsZip(selectedPaths, response.getOutputStream());
    }

    private RecentListItem recentItem(String path) throws IOException {
        return recentService.list(path, RecentSort.RECENT, SortDirection.DESC).stream()
                .filter(item -> item.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new NoSuchFileException(path));
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

    private String normalizeView(String view) {
        if ("grid".equalsIgnoreCase(view)) {
            return "grid";
        }
        if ("table".equalsIgnoreCase(view)) {
            return "table";
        }
        return "table";
    }

    private RecentSort normalizeSort(String sort) {
        return RecentSort.from(sort);
    }

    private SortDirection normalizeDirection(String direction, RecentSort sort) {
        if (direction == null || direction.isBlank()) {
            return sort == RecentSort.RECENT ? SortDirection.DESC : SortDirection.ASC;
        }
        return SortDirection.from(direction);
    }

    private int normalizePageSize(Integer size) {
        if (size == null) {
            return defaultPageSize();
        }
        return pageSizeOptions().contains(size) ? size : defaultPageSize();
    }

    private int defaultPageSize() {
        int configuredPageSize = nasProperties.getBrowser().getDefaultPageSize();
        return configuredPageSize > 0 ? configuredPageSize : FALLBACK_PAGE_SIZE;
    }

    private List<Integer> pageSizeOptions() {
        List<Integer> options = new ArrayList<>(PAGE_SIZE_OPTIONS);
        int defaultPageSize = defaultPageSize();
        if (!options.contains(defaultPageSize)) {
            options.add(defaultPageSize);
            options.sort(Integer::compareTo);
        }
        return List.copyOf(options);
    }

    private RecentPage pageFiles(List<RecentListItem> files, Integer requestedPage, int pageSize) {
        int totalItems = files.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int page = requestedPage == null ? 1 : requestedPage;
        page = Math.max(1, Math.min(page, totalPages));
        int startIndex = totalItems == 0 ? 0 : (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<RecentListItem> items = totalItems == 0 ? List.of() : files.subList(startIndex, endIndex);
        int startItem = totalItems == 0 ? 0 : startIndex + 1;
        return new RecentPage(items, page, pageSize, totalItems, totalPages, startItem, endIndex);
    }

    private String redirectToRecent(String query, String view, String sort, String direction, Integer page, Integer size) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/recent");
        if (query != null && !query.isBlank()) {
            builder.queryParam("q", query);
        }
        if ("grid".equalsIgnoreCase(view)) {
            builder.queryParam("view", "grid");
        }
        if (sort != null && !sort.isBlank() && !"recent".equalsIgnoreCase(sort)) {
            builder.queryParam("sort", RecentSort.from(sort).parameter());
        }
        RecentSort recentSort = RecentSort.from(sort);
        SortDirection sortDirection = normalizeDirection(direction, recentSort);
        if (sortDirection != (recentSort == RecentSort.RECENT ? SortDirection.DESC : SortDirection.ASC)) {
            builder.queryParam("dir", sortDirection.parameter());
        }
        int pageNumber = page == null ? 1 : Math.max(1, page);
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        int pageSize = normalizePageSize(size);
        if (pageSize != defaultPageSize()) {
            builder.queryParam("size", pageSize);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectUrl(String redirectViewName) {
        return redirectViewName.startsWith("redirect:") ? redirectViewName.substring("redirect:".length()) : redirectViewName;
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private String nextView(String view) {
        return "grid".equals(view) ? "table" : "grid";
    }

    private String viewToggleLabel(String view) {
        return "grid".equals(view) ? "Switch to table view" : "Switch to grid view";
    }

    private String viewToggleIcon(String view) {
        return "grid".equals(view) ? "fas fa-bars" : "fas fa-border-all";
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private String contentDisposition(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private String zipContentDisposition(List<String> paths) {
        String filename = paths.size() == 1
                ? paths.get(0).substring(paths.get(0).lastIndexOf('/') + 1) + ".zip"
                : "endervault-recent.zip";
        return contentDisposition(filename);
    }
}
