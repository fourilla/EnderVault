package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.ComicArchiveManifest;
import io.github.fourilla.endervault.filetool.ComicArchiveService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import io.github.fourilla.endervault.web.support.TextFileLoadResponse;
import io.github.fourilla.endervault.web.support.TextFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileDetailController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final FileToolService fileToolService;
    private final ComicArchiveService comicArchiveService;
    private final RecentService recentService;
    private final ActivityLogService activityLogService;

    public AdminFileDetailController(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            FileToolService fileToolService,
            ComicArchiveService comicArchiveService,
            RecentService recentService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.fileToolService = fileToolService;
        this.comicArchiveService = comicArchiveService;
        this.recentService = recentService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/detail")
    public String detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            Model model
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        FileToolDescriptor fileTool = fileToolService.resolve(detail);
        model.addAttribute("detail", detail);
        model.addAttribute("fileTool", fileTool);
        if (fileTool.text()) {
            model.addAttribute("textContent", fileToolService.readText(detail, storageService.resolveVaultFile(detail.path())));
        }
        if (fileTool.comic()) {
            ComicArchiveManifest comicManifest = comicArchiveService.manifest(storageService.resolveVaultFile(detail.path()));
            int comicPageIndex = comicArchiveService.normalizePage(comicManifest, comicPage);
            int comicPageNumber = comicManifest.empty() ? 0 : comicPageIndex + 1;
            model.addAttribute("comicManifest", comicManifest);
            model.addAttribute("comicPageIndex", comicPageIndex);
            model.addAttribute("comicPageNumber", comicPageNumber);
            model.addAttribute("comicPreviousPageNumber", Math.max(1, comicPageNumber - 1));
            model.addAttribute("comicNextPageNumber", Math.min(comicManifest.pageCount(), comicPageNumber + 1));
        }
        model.addAttribute("shares", shareLinkService.listForVaultPath(detail.path()));
        model.addAttribute("shareBaseUrl", shareBaseUrl());
        model.addAttribute("favorite", favoriteService.isFavorite(detail.path()));
        return "file-detail";
    }

    @PostMapping("/files/detail/text")
    public Object saveTextFromDetail(
            @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        fileToolService.writeText(detail, file, content);
        recentService.recordVaultPath(detail.path());
        activityLogService.record("TEXT_SAVE", request, detail.path(), null, "Saved text file " + detail.name());
        FlashNotification notification = FlashNotification.success("Text file saved.");
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.ok(notification));
        }
        FlashNotifications.success(redirectAttributes, notification.message());
        return redirectToDetail(detail.path());
    }

    @GetMapping("/files/detail/text/load")
    public ResponseEntity<TextFileLoadResponse> loadTextFromDetail(@RequestParam("path") String path) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        TextFileContent content = fileToolService.loadText(detail, file);
        if (!content.loaded()) {
            throw new StorageAccessException(content.message());
        }
        TextFilePayload payload = TextFilePayload.from(content);
        recentService.recordVaultPath(detail.path());
        return ResponseEntity.ok(TextFileLoadResponse.ok(payload));
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }

    private String shareBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString();
    }

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }

    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
