package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageEntryFilter;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fs")
public class StorageEntriesApiController {

    private static final Set<String> ENTRY_TYPES = Set.of("directory", "file");

    private final StorageService storageService;

    public StorageEntriesApiController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping(value = "/entries", produces = MediaType.APPLICATION_JSON_VALUE)
    public StorageEntriesPayload entries(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "types", defaultValue = "directory,file") String types,
            @RequestParam(value = "hidden", required = false) String hidden,
            HttpServletRequest request
    ) throws IOException {
        StorageEntryFilter filter = entryFilter(types);
        boolean showHidden = showHidden(request, hidden);
        DirectoryListing listing = storageService.list(
                StorageScope.VAULT,
                path,
                FileSort.NAME,
                SortDirection.ASC,
                showHidden,
                filter
        );
        return StorageEntriesPayload.from(listing);
    }

    private StorageEntryFilter entryFilter(String rawTypes) {
        Set<String> types = Arrays.stream((rawTypes == null ? "" : rawTypes).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (types.isEmpty() || !ENTRY_TYPES.containsAll(types)) {
            throw new StorageAccessException("Storage entry types must contain directory, file, or both.");
        }
        if (types.size() == 2) {
            return StorageEntryFilter.ALL;
        }
        return types.contains("directory") ? StorageEntryFilter.DIRECTORIES : StorageEntryFilter.FILES;
    }

    private boolean showHidden(HttpServletRequest request, String requestedHidden) {
        String hidden = requestedHidden == null
                ? BrowserPreferenceCookies.value(
                        request,
                        BrowserPreferenceCookies.FILES.hiddenCookie(),
                        this::normalizeHidden
                )
                : normalizeHidden(requestedHidden);
        return "show".equals(hidden);
    }

    private String normalizeHidden(String hidden) {
        return "show".equalsIgnoreCase(hidden) || "true".equalsIgnoreCase(hidden) ? "show" : "hide";
    }
}
