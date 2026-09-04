package io.github.fourilla.endervault.web.file.recent;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.recent.RecentSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecentBrowserPreferences {

    private static final int FALLBACK_PAGE_SIZE = 200;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final NasProperties nasProperties;

    public RecentBrowserPreferences(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public Values resolve(
            HttpServletRequest request,
            HttpServletResponse response,
            String view,
            String sort,
            String direction,
            String hidden,
            Integer pageSize
    ) {
        String normalizedView = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.RECENT.viewCookie(),
                view,
                this::normalizeView
        );
        RecentSort normalizedSort = RecentSort.from(BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.RECENT.sortCookie(),
                sort,
                value -> RecentSort.from(value).parameter()
        ));
        SortDirection normalizedDirection = SortDirection.from(BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.RECENT.directionCookie(),
                direction,
                value -> normalizeDirection(value, normalizedSort).parameter()
        ));
        String normalizedHidden = BrowserPreferenceCookies.value(
                request,
                response,
                BrowserPreferenceCookies.RECENT.hiddenCookie(),
                hidden,
                this::normalizeHidden
        );
        int normalizedPageSize = BrowserPreferenceCookies.intValue(
                request,
                response,
                BrowserPreferenceCookies.RECENT.pageSizeCookie(),
                pageSize,
                this::normalizePageSize
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

    private String normalizeView(String value) {
        return "grid".equalsIgnoreCase(value) ? "grid" : "table";
    }

    private SortDirection normalizeDirection(String value, RecentSort sort) {
        if (value == null || value.isBlank()) {
            return sort == RecentSort.RECENT ? SortDirection.DESC : SortDirection.ASC;
        }
        return SortDirection.from(value);
    }

    private String normalizeHidden(String value) {
        return "show".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) ? "show" : "hide";
    }

    private int normalizePageSize(Integer value) {
        return value != null && pageSizeOptions().contains(value) ? value : defaultPageSize();
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
            RecentSort sort,
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
