package io.github.fourilla.endervault.web.file.browser;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FileBrowserPreferences {

    private static final int FALLBACK_PAGE_SIZE = 200;
    private static final int READ_ONLY_PAGE_SIZE = 50;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;

    public FileBrowserPreferences(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public Values resolveFiles(
            HttpServletRequest request,
            HttpServletResponse response,
            String view,
            String sort,
            String direction,
            String hidden,
            Integer pageSize
    ) {
        return resolve(
                request,
                response,
                BrowserPreferenceCookies.FILES,
                view,
                sort,
                direction,
                hidden,
                pageSize,
                false
        );
    }

    public Values resolveReadOnly(
            HttpServletRequest request,
            HttpServletResponse response,
            String sort,
            String direction,
            String hidden,
            Integer pageSize
    ) {
        return resolve(
                request,
                response,
                BrowserPreferenceCookies.READ_ONLY,
                "table",
                sort,
                direction,
                hidden,
                pageSize,
                true
        );
    }

    public String rememberFilesView(
            HttpServletRequest request,
            HttpServletResponse response,
            String view
    ) {
        return BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.FILES.viewCookie(),
                view,
                this::normalizeView
        );
    }

    private Values resolve(
            HttpServletRequest request,
            HttpServletResponse response,
            BrowserPreferenceCookies.Scope scope,
            String view,
            String sort,
            String direction,
            String hidden,
            Integer pageSize,
            boolean readOnly
    ) {
        String normalizedView = readOnly
                ? "table"
                : BrowserPreferenceCookies.value(
                        request,
                        response,
                        scope.viewCookie(),
                        view,
                        this::normalizeView
                );
        FileSort normalizedSort = FileSort.from(BrowserPreferenceCookies.value(
                request,
                response,
                scope.sortCookie(),
                sort,
                value -> normalizeSort(value).parameter()
        ));
        SortDirection normalizedDirection = SortDirection.from(BrowserPreferenceCookies.value(
                request,
                response,
                scope.directionCookie(),
                direction,
                value -> normalizeDirection(value).parameter()
        ));
        String normalizedHidden = BrowserPreferenceCookies.value(
                request,
                response,
                scope.hiddenCookie(),
                hidden,
                this::normalizeHidden
        );
        int normalizedPageSize = BrowserPreferenceCookies.intValue(
                request,
                response,
                scope.pageSizeCookie(),
                pageSize,
                value -> normalizePageSize(value, readOnly)
        );
        return new Values(
                normalizedView,
                normalizedSort,
                normalizedDirection,
                "show".equals(normalizedHidden),
                normalizedPageSize,
                pageSizeOptions()
        );
    }

    private String normalizeView(String view) {
        if ("grid".equalsIgnoreCase(view)) {
            return "grid";
        }
        if ("table".equalsIgnoreCase(view)) {
            return "table";
        }
        return "grid".equalsIgnoreCase(nasProperties.getBrowser().getDefaultView()) ? "grid" : "table";
    }

    private FileSort normalizeSort(String sort) {
        return sort == null || sort.isBlank()
                ? FileSort.from(nasProperties.getBrowser().getDefaultSort())
                : FileSort.from(sort);
    }

    private SortDirection normalizeDirection(String direction) {
        return direction == null || direction.isBlank()
                ? SortDirection.from(nasProperties.getBrowser().getDefaultDirection())
                : SortDirection.from(direction);
    }

    private String normalizeHidden(String hidden) {
        return "show".equalsIgnoreCase(hidden) || "true".equalsIgnoreCase(hidden) ? "show" : "hide";
    }

    private int normalizePageSize(Integer pageSize, boolean readOnly) {
        if (pageSize == null) {
            return readOnly ? READ_ONLY_PAGE_SIZE : defaultPageSize();
        }
        return pageSizeOptions().contains(pageSize) ? pageSize : defaultPageSize();
    }

    private int defaultPageSize() {
        int configured = nasProperties.getBrowser().getDefaultPageSize();
        return configured > 0 ? configured : FALLBACK_PAGE_SIZE;
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

    public record Values(
            String view,
            FileSort sort,
            SortDirection direction,
            boolean showHidden,
            int pageSize,
            List<Integer> pageSizeOptions
    ) {
        public String hiddenMode() {
            return showHidden ? "show" : "hide";
        }
    }
}
