package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.FileSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminFileController {

    private static final int FALLBACK_PAGE_SIZE = 200;
    private static final int READ_ONLY_PAGE_SIZE = 50;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final TransferBufferService transferBufferService;

    public AdminFileController(
            NasProperties nasProperties,
            StorageService storageService,
            FavoriteService favoriteService,
            RecentService recentService,
            TransferBufferService transferBufferService
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.transferBufferService = transferBufferService;
    }

    @GetMapping("/files")
    public String files(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        String normalizedView = fileBrowserView(request, response, view);
        FileSort fileSort = fileBrowserSort(request, response, sort);
        SortDirection sortDirection = fileBrowserDirection(request, response, direction);
        int pageSize = fileBrowserPageSize(request, response, size);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path, fileSort, sortDirection);
        recentService.recordVaultPath(listing.path());
        FilePage filePage = pageFiles(listing.files(), page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("view", normalizedView);
        model.addAttribute("nextView", nextView(normalizedView));
        model.addAttribute("viewToggleLabel", viewToggleLabel(normalizedView));
        model.addAttribute("viewToggleIcon", viewToggleIcon(normalizedView));
        model.addAttribute("sort", fileSort.parameter());
        model.addAttribute("dir", sortDirection.parameter());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("filePage", filePage);
        model.addAttribute("favoritePaths", favoriteService.favoritePaths());
        model.addAttribute("transferBuffer", transferBufferService.current(request.getSession(false)));
        return "files";
    }

    @GetMapping("/files/search")
    public String search(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        int pageSize = fileBrowserPageSize(request, response, size);
        String normalizedQuery = normalizeSearchQuery(query);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path);
        List<FileItem> results = normalizedQuery.isEmpty()
                ? List.of()
                : storageService.search(StorageScope.VAULT, listing.path(), normalizedQuery);
        FilePage resultPage = pageFiles(results, page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("query", normalizedQuery);
        model.addAttribute("searchPerformed", !normalizedQuery.isEmpty());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("resultPage", resultPage);
        return "search";
    }

    @GetMapping("/files/read-only")
    public String readOnly(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        FileSort fileSort = readOnlySort(request, response, sort);
        SortDirection sortDirection = readOnlyDirection(request, response, direction);
        int pageSize = readOnlyPageSize(request, response, size);
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path, fileSort, sortDirection);
        FilePage filePage = pageFiles(listing.files(), page, pageSize);

        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("sort", fileSort.parameter());
        model.addAttribute("dir", sortDirection.parameter());
        model.addAttribute("pageSizes", pageSizeOptions());
        model.addAttribute("filePage", filePage);
        return "read-only";
    }

    @PostMapping("/files/preferences/reset")
    public String resetBrowserPreferences(
            @RequestParam(value = "target", required = false) String target,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if ("read-only".equalsIgnoreCase(target)) {
            BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.READ_ONLY);
            FlashNotifications.success(redirectAttributes, "Read-only preferences reset.");
            return redirectToReadOnly(path);
        }

        if ("recent".equalsIgnoreCase(target)) {
            BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.RECENT);
            FlashNotifications.success(redirectAttributes, "Recent preferences reset.");
            return redirectToRecent(query);
        }

        BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.FILES);
        FlashNotifications.success(redirectAttributes, "File browser preferences reset.");
        return redirectToFiles(path);
    }

    private String redirectToFiles(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectToRecent(String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/recent");
        if (query != null && !query.isBlank()) {
            builder.queryParam("q", query);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectToReadOnly(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/read-only");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String normalizeView(String view) {
        if ("grid".equalsIgnoreCase(view)) {
            return "grid";
        }
        if ("table".equalsIgnoreCase(view)) {
            return "table";
        }
        return defaultView();
    }

    private String defaultView() {
        return "grid".equalsIgnoreCase(nasProperties.getBrowser().getDefaultView()) ? "grid" : "table";
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

    private String fileBrowserView(HttpServletRequest request, HttpServletResponse response, String view) {
        return BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.viewCookie(),
                view,
                this::normalizeView
        );
    }

    private FileSort fileBrowserSort(HttpServletRequest request, HttpServletResponse response, String sort) {
        String normalizedSort = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.sortCookie(),
                sort,
                value -> normalizeSort(value).parameter()
        );
        return FileSort.from(normalizedSort);
    }

    private SortDirection fileBrowserDirection(HttpServletRequest request, HttpServletResponse response, String direction) {
        String normalizedDirection = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.directionCookie(),
                direction,
                value -> normalizeDirection(value).parameter()
        );
        return SortDirection.from(normalizedDirection);
    }

    private int fileBrowserPageSize(HttpServletRequest request, HttpServletResponse response, Integer size) {
        return BrowserPreferenceCookies.intValue(
                request,
                response,
                BrowserPreferenceCookies.FILES.pageSizeCookie(),
                size,
                this::normalizePageSize
        );
    }

    private FileSort readOnlySort(HttpServletRequest request, HttpServletResponse response, String sort) {
        String normalizedSort = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.READ_ONLY.sortCookie(),
                sort,
                value -> normalizeSort(value).parameter()
        );
        return FileSort.from(normalizedSort);
    }

    private SortDirection readOnlyDirection(HttpServletRequest request, HttpServletResponse response, String direction) {
        String normalizedDirection = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.READ_ONLY.directionCookie(),
                direction,
                value -> normalizeDirection(value).parameter()
        );
        return SortDirection.from(normalizedDirection);
    }

    private int readOnlyPageSize(HttpServletRequest request, HttpServletResponse response, Integer size) {
        return BrowserPreferenceCookies.intValue(
                request,
                response,
                BrowserPreferenceCookies.READ_ONLY.pageSizeCookie(),
                size,
                value -> value == null ? READ_ONLY_PAGE_SIZE : normalizePageSize(value)
        );
    }

    private FileSort normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort();
        }
        return FileSort.from(sort);
    }

    private FileSort defaultSort() {
        return FileSort.from(nasProperties.getBrowser().getDefaultSort());
    }

    private SortDirection normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return defaultDirection();
        }
        return SortDirection.from(direction);
    }

    private SortDirection defaultDirection() {
        return SortDirection.from(nasProperties.getBrowser().getDefaultDirection());
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

    private String normalizeSearchQuery(String query) {
        return query == null ? "" : query.trim();
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

    private FilePage pageFiles(List<FileItem> files, Integer requestedPage, int pageSize) {
        int totalItems = files.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        int page = requestedPage == null ? 1 : requestedPage;
        page = Math.max(1, Math.min(page, totalPages));
        int startIndex = totalItems == 0 ? 0 : (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<FileItem> items = totalItems == 0 ? List.of() : files.subList(startIndex, endIndex);
        int startItem = totalItems == 0 ? 0 : startIndex + 1;
        return new FilePage(items, page, pageSize, totalItems, totalPages, startItem, endIndex);
    }
}
