package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.recent.RecentSort;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import io.github.fourilla.endervault.web.support.FileResponseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminRecentController {

    private final RecentService recentService;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final FileResponseService fileResponseService;
    private final AdminSpaViewService adminSpaViewService;

    public AdminRecentController(
            RecentService recentService,
            StorageService storageService,
            ActivityLogService activityLogService,
            FileResponseService fileResponseService,
            AdminSpaViewService adminSpaViewService
    ) {
        this.recentService = recentService;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.fileResponseService = fileResponseService;
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/files/recent")
    public String recent(Model model) {
        return adminSpaViewService.render(model);
    }

    @GetMapping("/files/recent/download.zip")
    public void downloadSelected(
            @RequestParam(value = "paths", required = false) List<String> paths,
            @RequestHeader HttpHeaders headers,
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
                Path file = storageService.resolveVaultFile(item.path());
                fileResponseService.writeAttachment(file, headers, response);
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
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "none");
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
